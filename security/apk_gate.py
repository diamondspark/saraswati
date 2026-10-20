#!/usr/bin/env python3
"""APK manifest gate.

Standalone, stdlib-only tool (run with ``python3 -I``) that extracts the
security-relevant facts from an Android manifest and checks them against an
allowlist. Used in Phase 1 to prove the gms-flavor manifest overlay actually
removes the permissions and exported components the plan requires removed,
and to catch any future regression before it reaches a device.

Beyond permissions and exported components, the gate pins every component's
intent filters (actions, categories, data scheme/host/port/path/pathPrefix/
pathPattern/mimeType, autoVerify), the ``<queries>`` block and the three
``<application>`` resource references (dataExtractionRules, fullBackupContent,
networkSecurityConfig), so a deep-link or package-visibility regression fails
the build. When the allowlist carries a ``hub_host_agreement`` section, it also
asserts that the family hub's host is the same in ``app/build.gradle.kts``
(``HUB_BASE_URL`` of the gms flavor), the manifest's pairing-link filter, the
network security config and ``security/egress-allowlist.json``.

Usage:
    python3 -I security/apk_gate.py check --apk <apk> --allowlist <allowlist.json>
    python3 -I security/apk_gate.py check --xml <manifest.xml> --allowlist <allowlist.json>
    python3 -I security/apk_gate.py snapshot --apk <apk>

Re-pinning after a deliberate manifest change: build the release APK, run
``snapshot`` and copy ``intent_filters``, ``queries``, ``references`` and
``reference_ids`` from its output into the allowlist (a human reviews that
diff).

``references`` are pinned by resource *name* (``@xml/network_security_config``):
a built APK's manifest holds raw ids (``@ref/0x7f150006``) that shift whenever
any xml resource is added or removed, so the gate resolves them through the
APK's own resource table (``aapt2 dump resources``, found under
``$ANDROID_HOME`` / ``$ANDROID_SDK_ROOT`` ``build-tools/*``, then ``PATH``).
``reference_ids`` keeps the raw ids as a fallback pin. When aapt2 cannot be run
the gate says so on stderr and compares the raw ids instead, so it fails only if
a raw id also differs from its pin (which an unrelated resource change can cause;
re-snapshot with aapt2 available). A source manifest names its resources, so
``--xml`` never needs aapt2.

Exit codes for ``check``: 0 pass, 1 violations found, 2 usage error.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# The repository root, so the hub-host agreement check finds app/build.gradle.kts
# and security/egress-allowlist.json whatever directory the gate is run from.
ROOT = Path(__file__).resolve().parent.parent

# <application> attributes whose value is a resource reference the gate pins.
_APPLICATION_REFERENCES = ("dataExtractionRules", "fullBackupContent", "networkSecurityConfig")

# Intent-filter attributes that change which intents match (label/icon do not).
_FILTER_ATTRS = ("priority", "order")

# The name `network_security_config.xml` is given in the resource table; the
# gate reads the APK's own copy of it by this name.
_NSC_RESOURCE_NAME = "network_security_config"

# apkanalyzer prints a resource reference it cannot name as @ref/0x7f150006.
_RAW_REFERENCE = re.compile(r"@(?:ref/)?(0x[0-9a-fA-F]+)")

# One resource line of `aapt2 dump resources`: "    resource 0x7f150006 xml/network_security_config".
_AAPT2_RESOURCE_LINE = re.compile(r"^\s*resource (0x[0-9a-fA-F]+) (\S+/\S+)\s*$")

_AAPT2_TIMEOUT_SECONDS = 120

# Component tags that can be exported, matching the manifest schema's
# <application> children that carry android:exported.
_COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


@dataclasses.dataclass(frozen=True)
class ManifestFacts:
    package: str
    permissions: frozenset[str]
    exported_components: frozenset[str]
    allow_backup: bool | None
    debuggable: bool | None
    # "tag:name" -> canonical intent filters (see _canonical_filter), sorted.
    intent_filters: dict = dataclasses.field(default_factory=dict)
    # {"packages": [...], "intents": [canonical filters], "providers": [...]}
    queries: dict = dataclasses.field(default_factory=dict)
    # <application> attribute name -> reference string as found, only those present: "@xml/name"
    # when the manifest names its resources (a source manifest, or after resolve_references),
    # "@ref/0x7f150006" when it holds raw ids (apkanalyzer's view of a built APK).
    references: dict = dataclasses.field(default_factory=dict)
    # The raw-id subset of what the manifest held, kept as the fallback pin.
    reference_ids: dict = dataclasses.field(default_factory=dict)
    # False while `references` still holds raw ids (aapt2 has not resolved them).
    references_are_names: bool = True


def _android_attr(elem: ET.Element, name: str) -> str | None:
    return elem.get(f"{{{ANDROID_NS}}}{name}")


def _to_bool(value: str | None) -> bool | None:
    if value is None:
        return None
    return value == "true"


def _filter_key(canonical: dict) -> str:
    return json.dumps(canonical, sort_keys=True, separators=(",", ":"))


def _canonical_filter(elem: ET.Element) -> dict:
    """Canonical, order-independent form of an ``<intent-filter>`` or ``<intent>``.

    Android merges every ``<data>`` element of a filter into one cross product,
    so the data is the set of values per attribute, whichever way the manifest
    spreads them over elements. Every ``android:`` attribute a ``<data>`` element
    carries is kept (path, pathPrefix, pathPattern, host, mimeType, ...): widening
    ``path="/pair"`` to ``pathPrefix="/pair"`` must change the form.
    """
    actions = sorted({n for a in elem.findall("action") if (n := _android_attr(a, "name"))})
    categories = sorted({n for c in elem.findall("category") if (n := _android_attr(c, "name"))})
    data: dict[str, set[str]] = {}
    for d in elem.findall("data"):
        for attr, value in d.attrib.items():
            if attr.startswith(f"{{{ANDROID_NS}}}"):
                data.setdefault(attr.removeprefix(f"{{{ANDROID_NS}}}"), set()).add(value)
    canonical: dict = {
        "actions": actions,
        "categories": categories,
        "data": {k: sorted(v) for k, v in sorted(data.items())},
        "autoVerify": _android_attr(elem, "autoVerify") == "true",
    }
    for attr in _FILTER_ATTRS:
        value = _android_attr(elem, attr)
        if value is not None:
            canonical[attr] = value
    return canonical


def _canonical_filters(elems: list[ET.Element]) -> list[dict]:
    return sorted((_canonical_filter(e) for e in elems), key=_filter_key)


def _parse_queries(root: ET.Element) -> dict:
    packages: set[str] = set()
    providers: set[str] = set()
    intents: list[ET.Element] = []
    for queries in root.findall("queries"):
        packages.update(n for e in queries.findall("package") if (n := _android_attr(e, "name")))
        providers.update(n for e in queries.findall("provider") if (n := _android_attr(e, "authorities")))
        intents.extend(queries.findall("intent"))
    return {
        "packages": sorted(packages),
        "intents": _canonical_filters(intents),
        "providers": sorted(providers),
    }


def parse_manifest(xml_text: str) -> ManifestFacts:
    """Extract the facts the gate cares about from manifest XML text.

    Accepts both a raw source AndroidManifest.xml and the textual XML
    produced by ``apkanalyzer manifest print`` for a built APK.
    """
    root = ET.fromstring(xml_text)

    package = root.get("package") or ""

    permissions: set[str] = set()
    for perm in root.findall("uses-permission"):
        name = _android_attr(perm, "name")
        if name:
            permissions.add(name)

    exported_components: set[str] = set()
    intent_filters: dict[str, list[dict]] = {}
    references: dict[str, str] = {}
    allow_backup: bool | None = None
    debuggable: bool | None = None

    application = root.find("application")
    if application is not None:
        allow_backup = _to_bool(_android_attr(application, "allowBackup"))
        debuggable = _to_bool(_android_attr(application, "debuggable"))
        for attr in _APPLICATION_REFERENCES:
            value = _android_attr(application, attr)
            if value is not None:
                references[attr] = value

        for tag in _COMPONENT_TAGS:
            for elem in application.findall(tag):
                name = _android_attr(elem, "name")
                if not name:
                    continue
                filters = elem.findall("intent-filter")
                exported = _android_attr(elem, "exported")
                # A component with an intent filter and no explicit android:exported
                # is exported on every target below Android 12, so it counts as such.
                if exported == "true" or (exported is None and filters and tag != "provider"):
                    exported_components.add(f"{tag}:{name}")
                if filters:
                    intent_filters[f"{tag}:{name}"] = _canonical_filters(filters)

    reference_ids = {attr: value for attr, value in references.items() if _RAW_REFERENCE.fullmatch(value)}

    return ManifestFacts(
        package=package,
        permissions=frozenset(permissions),
        exported_components=frozenset(exported_components),
        allow_backup=allow_backup,
        debuggable=debuggable,
        intent_filters=intent_filters,
        queries=_parse_queries(root),
        references=references,
        reference_ids=reference_ids,
        references_are_names=not reference_ids,
    )


def parse_aapt2_resource_names(dump_text: str) -> dict[str, str]:
    """``{"0x7f150006": "@xml/network_security_config"}`` from ``aapt2 dump resources`` text.

    Only the ``resource <id> <type>/<name>`` lines count; the value and file lines
    under them do not. Ids are lower case; the first line for an id wins.
    """
    names: dict[str, str] = {}
    for line in dump_text.splitlines():
        match = _AAPT2_RESOURCE_LINE.match(line)
        if match:
            names.setdefault(match.group(1).lower(), "@" + match.group(2))
    return names


def resolve_references(facts: ManifestFacts, names: dict[str, str]) -> ManifestFacts:
    """[facts] with each raw ``@ref/0x...`` reference replaced by its resource name.

    An id that is not in [names] stays raw, so the name comparison fails on it and
    check() says why. Facts that already hold names are returned as they are.
    """
    if facts.references_are_names:
        return facts
    references = {}
    for attr, value in facts.references.items():
        match = _RAW_REFERENCE.fullmatch(value)
        references[attr] = names.get(match.group(1).lower(), value) if match else value
    return dataclasses.replace(facts, references=references, references_are_names=True)


def find_aapt2(env: dict | None = None) -> str | None:
    """Path of an aapt2 to run: the newest ``build-tools/*/aapt2`` under ``$ANDROID_HOME``,
    then under ``$ANDROID_SDK_ROOT``, then whatever ``PATH`` offers; None if there is none."""
    env = os.environ if env is None else env

    def version(directory: Path) -> tuple[int, ...]:
        return tuple(int(n) for n in re.findall(r"\d+", directory.name))

    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = env.get(variable)
        if not root:
            continue
        build_tools = sorted((d for d in (Path(root) / "build-tools").glob("*") if d.is_dir()), key=version, reverse=True)
        for directory in build_tools:
            candidate = directory / "aapt2"
            if candidate.is_file() and os.access(candidate, os.X_OK):
                return str(candidate)
    return shutil.which("aapt2", path=env.get("PATH") or None) if env.get("PATH") else None


def check(facts: ManifestFacts, allowlist: dict) -> list[str]:
    """Return a list of violation strings; empty means the manifest passes."""
    violations: list[str] = []

    expected_package = allowlist.get("package")
    if expected_package is not None and facts.package != expected_package:
        violations.append(
            f"package mismatch: expected {expected_package}, found {facts.package}"
        )

    forbidden_permissions = set(allowlist.get("forbidden_permissions", []))
    allowed_permissions = set(allowlist.get("permissions", []))
    for permission in sorted(facts.permissions):
        if permission in forbidden_permissions:
            violations.append(f"forbidden permission: {permission}")
        elif permission not in allowed_permissions:
            violations.append(f"unexpected permission: {permission}")

    allowed_components = set(allowlist.get("exported_components", []))
    for component in sorted(facts.exported_components):
        if component not in allowed_components:
            violations.append(f"unexpected exported component: {component}")

    if facts.allow_backup is True:
        violations.append("allowBackup must be false")

    if facts.debuggable is True:
        violations.append("debuggable must not be true")

    violations.extend(_check_pins(facts, allowlist))

    return violations


_PIN_HINT = "run `apk_gate.py snapshot` on the release APK and review the diff"


def _check_pins(facts: ManifestFacts, allowlist: dict) -> list[str]:
    """Compare intent filters, ``<queries>`` and resource references with their pins.

    The pins are mandatory: an allowlist that does not carry them would turn
    the whole check off, so its absence is itself a violation.
    """
    violations: list[str] = []

    pinned_filters = allowlist.get("intent_filters")
    if pinned_filters is None:
        violations.append(f"allowlist has no intent_filters pin ({_PIN_HINT})")
    else:
        for owner in sorted(set(pinned_filters) | set(facts.intent_filters)):
            violations.extend(
                _diff_filters(
                    f"intent-filter on {owner}",
                    pinned_filters.get(owner, []),
                    facts.intent_filters.get(owner, []),
                )
            )

    pinned_queries = allowlist.get("queries")
    if pinned_queries is None:
        violations.append(f"allowlist has no queries pin ({_PIN_HINT})")
    else:
        for section, label in (("packages", "query package"), ("providers", "query provider")):
            pinned = set(pinned_queries.get(section, []))
            found = set(facts.queries.get(section, []))
            violations.extend(f"unexpected {label}: {item}" for item in sorted(found - pinned))
            violations.extend(f"missing {label}: {item}" for item in sorted(pinned - found))
        violations.extend(
            _diff_filters("query intent", pinned_queries.get("intents", []), facts.queries.get("intents", []))
        )

    pinned_references = allowlist.get("references")
    if pinned_references is None:
        violations.append(f"allowlist has no references pin ({_PIN_HINT})")
        pinned_references = {}
    if facts.references_are_names:
        violations.extend(_diff_references(pinned_references, facts.references))
        for attr, value in sorted(facts.references.items()):
            match = _RAW_REFERENCE.fullmatch(value)
            if match and value != pinned_references.get(attr):
                violations.append(f"note: {value} is not in the APK's resource table, so it has no name to compare")
    else:
        # Only raw ids are known (aapt2 could not run): the names cannot be checked, so the ids are,
        # against the pins taken from the same APK. They fail only when an id really differs.
        pinned_ids = allowlist.get("reference_ids")
        if pinned_ids is None:
            violations.append(
                f"allowlist has no reference_ids pin, which comparing raw resource ids needs ({_PIN_HINT})"
            )
        else:
            changed = _diff_references(pinned_ids, facts.reference_ids)
            violations.extend(changed)
            if changed:
                violations.append(
                    "note: a raw @ref/0x... id shifts when an xml resource is added or removed, and aapt2 was not "
                    f"available to compare names instead; if that is the only change, {_PIN_HINT}"
                )

    return violations


def _diff_references(pinned: dict, found: dict) -> list[str]:
    return [
        f"application {attr} changed: pinned {pinned.get(attr) or 'none'}, found {found.get(attr) or 'none'}"
        for attr in sorted(set(pinned) | set(found))
        if pinned.get(attr) != found.get(attr)
    ]


def _diff_filters(label: str, pinned: list, found: list) -> list[str]:
    pinned_by_key = {_filter_key(f): f for f in pinned}
    found_by_key = {_filter_key(f): f for f in found}
    violations = [f"unexpected {label}: {key}" for key in sorted(found_by_key.keys() - pinned_by_key.keys())]
    violations += [f"missing {label}: {key}" for key in sorted(pinned_by_key.keys() - found_by_key.keys())]
    return violations


def _hub_host_of(url: str) -> str | None:
    """The bare host of an https URL with no port, path, user info or query; else None."""
    parts = urlsplit(url)
    if parts.scheme != "https" or not parts.hostname or parts.port is not None or parts.username:
        return None
    if parts.path not in ("", "/") or parts.query or parts.fragment:
        return None
    return parts.hostname


def parse_hub_base_url(gradle_text: str, flavor: str = "gms") -> str:
    """The ``HUB_BASE_URL`` literal of [flavor] in app/build.gradle.kts, with ``$name`` resolved.

    Raises ValueError when it cannot be read: a gate that cannot find the value
    must fail, not pass.
    """
    block = re.search(r'getByName\("' + re.escape(flavor) + r'"\)\s*\{', gradle_text)
    if not block:
        raise ValueError(f'no getByName("{flavor}") block in the gradle file')
    depth, i = 1, block.end()
    while i < len(gradle_text) and depth:
        depth += {"{": 1, "}": -1}.get(gradle_text[i], 0)
        i += 1
    body = gradle_text[block.end() : i]
    field = re.search(r'buildConfigField\(\s*"String"\s*,\s*"HUB_BASE_URL"\s*,\s*"\\"(?P<value>[^"]*?)\\""\s*\)', body)
    if not field:
        raise ValueError(f"no HUB_BASE_URL buildConfigField in the {flavor} block")
    value = field.group("value")
    reference = re.fullmatch(r"\$\{?(\w+)\}?", value)
    if reference:
        variable = re.search(r"val\s+" + re.escape(reference.group(1)) + r'\s*=\s*"([^"$]*)"', gradle_text)
        if not variable:
            raise ValueError(f"HUB_BASE_URL refers to ${reference.group(1)}, which is not a plain string val")
        value = variable.group(1)
    return value


def parse_network_config_domains(nsc_xml_text: str) -> list[tuple[str, bool]]:
    """``(host, includeSubdomains)`` of every ``<domain>`` in a network security config."""
    root = ET.fromstring(nsc_xml_text)
    return [
        ((d.text or "").strip(), d.get("includeSubdomains") == "true")
        for d in root.iter("domain")
        if (d.text or "").strip()
    ]


def check_hub_agreement(
    facts: ManifestFacts,
    section: dict,
    *,
    gradle_text: str,
    nsc_xml_text: str,
    egress_allowlist: dict,
) -> list[str]:
    """The hub host must be the same in the gradle HUB_BASE_URL, the pairing-link
    intent filter, the network security config and the egress allowlist."""
    try:
        base_url = parse_hub_base_url(gradle_text, section.get("flavor", "gms"))
    except ValueError as exc:
        return [f"hub host: {exc}"]
    host = _hub_host_of(base_url)
    if host is None:
        return [f"hub host: HUB_BASE_URL {base_url!r} is not a bare https URL"]

    violations: list[str] = []

    pairing_path = section.get("pairing_path", "/pair")
    pairing_hosts: set[str] = set()
    for filters in facts.intent_filters.values():
        for canonical in filters:
            data = canonical.get("data", {})
            if pairing_path in data.get("path", []):
                pairing_hosts.update(data.get("host", []))
    if not pairing_hosts:
        violations.append(f"hub host: no intent filter for the pairing link path {pairing_path}")
    elif pairing_hosts != {host}:
        violations.append(
            f"hub host mismatch: HUB_BASE_URL host is {host}, "
            f"the manifest's pairing-link host is {', '.join(sorted(pairing_hosts))}"
        )

    nsc_domains = parse_network_config_domains(nsc_xml_text)
    if (host, False) not in nsc_domains:
        violations.append(
            f"hub host mismatch: network_security_config has no exact (includeSubdomains=false) entry for {host}"
            f" (parsed {len(nsc_domains)} domains; hub-like: {[d for d in nsc_domains if 'workers' in d[0]]})"
        )

    allowed = {(entry.get("name"), bool(entry.get("subdomains"))) for entry in egress_allowlist.get("allowed", [])}
    if (host, False) not in allowed:
        violations.append(f"hub host mismatch: egress-allowlist.json has no exact entry for {host}")

    return violations


def _facts_to_jsonable(facts: ManifestFacts) -> dict:
    snapshot = {
        "package": facts.package,
        "permissions": sorted(facts.permissions),
        "exported_components": sorted(facts.exported_components),
        "allow_backup": facts.allow_backup,
        "debuggable": facts.debuggable,
        "intent_filters": {owner: facts.intent_filters[owner] for owner in sorted(facts.intent_filters)},
        "queries": facts.queries,
        "reference_ids": facts.reference_ids,
    }
    # Raw ids are not names: without aapt2 the name pins are left out rather than filled with ids.
    if facts.references_are_names:
        snapshot["references"] = facts.references
    return snapshot


def _run_apkanalyzer(*args: str) -> str:
    result = subprocess.run(
        ["apkanalyzer", *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"apkanalyzer {' '.join(args[:2])} failed (exit {result.returncode}): "
            f"{result.stderr.strip()}"
        )
    return result.stdout


def _run_apkanalyzer_manifest_print(apk_path: str) -> str:
    return _run_apkanalyzer("manifest", "print", apk_path)


def _read_apk_network_config(apk_path: str) -> str:
    """The compiled network security config inside the APK, as text.

    The resource path is obfuscated in a release build (res/8G.xml), so it is
    looked up by resource name, never guessed.
    """
    path = _run_apkanalyzer(
        "resources", "value", "--config", "default", "--type", "xml",
        "--name", _NSC_RESOURCE_NAME, apk_path,
    ).strip()
    if not path:
        raise RuntimeError(f"no xml resource named {_NSC_RESOURCE_NAME} in {apk_path}")
    return _run_apkanalyzer("resources", "xml", "--file", path, apk_path)


def _run_aapt2_resources(aapt2: str, apk_path: str) -> str:
    try:
        result = subprocess.run(
            [aapt2, "dump", "resources", apk_path],
            capture_output=True,
            text=True,
            check=False,
            timeout=_AAPT2_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise RuntimeError(f"could not run {aapt2}: {exc}") from exc
    if result.returncode != 0:
        raise RuntimeError(f"aapt2 dump resources exited {result.returncode}: {result.stderr.strip()[:200]}")
    return result.stdout


_FALLBACK_NOTE = (
    "comparing raw resource ids against the pinned ids instead of resource names. Ids shift when an xml "
    "resource is added or removed; run with the Android SDK build-tools available (ANDROID_HOME / "
    "ANDROID_SDK_ROOT) to compare names."
)


def _resolve_apk_references(facts: ManifestFacts, apk_path: str) -> tuple[ManifestFacts, list[str]]:
    """[facts] with the APK's raw resource ids turned into names, and the warnings to print.

    Whenever aapt2 is missing or fails, the facts come back unchanged (raw ids) with a warning,
    and check() then compares the ids against their pins.
    """
    if facts.references_are_names:
        return facts, []
    aapt2 = find_aapt2()
    if aapt2 is None:
        return facts, [
            "warning: aapt2 not found (looked in build-tools under ANDROID_HOME and ANDROID_SDK_ROOT, then PATH): "
            + _FALLBACK_NOTE
        ]
    try:
        table = parse_aapt2_resource_names(_run_aapt2_resources(aapt2, apk_path))
    except RuntimeError as exc:
        return facts, [f"warning: aapt2 failed ({exc}): " + _FALLBACK_NOTE]
    return resolve_references(facts, table), []


def _load_manifest_text(args: argparse.Namespace) -> str:
    if args.xml:
        with open(args.xml, encoding="utf-8") as fh:
            return fh.read()
    return _run_apkanalyzer_manifest_print(args.apk)


def _hub_agreement_violations(
    facts: ManifestFacts, section: dict, args: argparse.Namespace
) -> list[str]:
    """Load the repository files the hub-host agreement compares and run the check.

    Paths in the allowlist section are relative to the repository root. The
    network security config comes from the APK itself when one is given (what
    the platform will actually enforce), from the repository's copy otherwise.
    """
    gradle_text = (ROOT / section.get("build_gradle", "app/build.gradle.kts")).read_text(encoding="utf-8")
    egress = json.loads(
        (ROOT / section.get("egress_allowlist", "security/egress-allowlist.json")).read_text(encoding="utf-8")
    )
    if args.apk:
        nsc_text = _read_apk_network_config(args.apk)
    else:
        nsc_text = (
            ROOT / section.get("network_security_config", "app/src/main/res/xml/network_security_config.xml")
        ).read_text(encoding="utf-8")
    return check_hub_agreement(
        facts, section, gradle_text=gradle_text, nsc_xml_text=nsc_text, egress_allowlist=egress
    )


def _cmd_check(args: argparse.Namespace) -> int:
    if not args.apk and not args.xml:
        print("check: one of --apk or --xml is required", file=sys.stderr)
        return 2
    if args.apk and args.xml:
        print("check: --apk and --xml are mutually exclusive", file=sys.stderr)
        return 2

    try:
        xml_text = _load_manifest_text(args)
    except (OSError, RuntimeError) as exc:
        print(f"check: {exc}", file=sys.stderr)
        return 2

    try:
        with open(args.allowlist, encoding="utf-8") as fh:
            allowlist = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"check: could not load allowlist {args.allowlist}: {exc}", file=sys.stderr)
        return 2

    try:
        facts = parse_manifest(xml_text)
    except ET.ParseError as exc:
        print(f"check: could not parse manifest: {exc}", file=sys.stderr)
        return 2

    if args.apk:
        facts, warnings = _resolve_apk_references(facts, args.apk)
        for warning in warnings:
            print(warning, file=sys.stderr)

    violations = check(facts, allowlist)

    section = allowlist.get("hub_host_agreement")
    if section is not None:
        try:
            violations.extend(_hub_agreement_violations(facts, section, args))
        except (OSError, RuntimeError, json.JSONDecodeError, ET.ParseError) as exc:
            print(f"check: could not read the hub host inputs: {exc}", file=sys.stderr)
            return 2

    if violations:
        for violation in violations:
            print(violation)
        return 1
    return 0


def _cmd_snapshot(args: argparse.Namespace) -> int:
    if not args.apk:
        print("snapshot: --apk is required", file=sys.stderr)
        return 2

    try:
        xml_text = _run_apkanalyzer_manifest_print(args.apk)
    except RuntimeError as exc:
        print(f"snapshot: {exc}", file=sys.stderr)
        return 2

    try:
        facts = parse_manifest(xml_text)
    except ET.ParseError as exc:
        print(f"snapshot: could not parse manifest: {exc}", file=sys.stderr)
        return 2

    facts, warnings = _resolve_apk_references(facts, args.apk)
    for warning in warnings:
        print(warning, file=sys.stderr)
    if not facts.references_are_names:
        print("warning: `references` (the name pins) is left out of this snapshot; do not pin raw ids as names", file=sys.stderr)

    print(json.dumps(_facts_to_jsonable(facts), indent=2, sort_keys=True))
    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="apk_gate.py")
    subparsers = parser.add_subparsers(dest="command")

    check_parser = subparsers.add_parser("check", help="check a manifest against an allowlist")
    check_parser.add_argument("--apk", help="path to an APK file")
    check_parser.add_argument("--xml", help="path to a manifest XML file (fixtures)")
    check_parser.add_argument("--allowlist", required=True, help="path to the allowlist JSON")
    check_parser.set_defaults(func=_cmd_check)

    snapshot_parser = subparsers.add_parser("snapshot", help="print manifest facts as JSON")
    snapshot_parser.add_argument("--apk", required=True, help="path to an APK file")
    snapshot_parser.set_defaults(func=_cmd_snapshot)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "command", None):
        parser.print_usage(sys.stderr)
        return 2
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

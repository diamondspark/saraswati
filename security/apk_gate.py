#!/usr/bin/env python3
"""APK manifest gate.

Standalone, stdlib-only tool (run with ``python3 -I``) that extracts the
security-relevant facts from an Android manifest and checks them against an
allowlist. Used in Phase 1 to prove the gms-flavor manifest overlay actually
removes the permissions and exported components the plan requires removed,
and to catch any future regression before it reaches a device.

Usage:
    python3 -I security/apk_gate.py check --apk <apk> --allowlist <allowlist.json>
    python3 -I security/apk_gate.py check --xml <manifest.xml> --allowlist <allowlist.json>
    python3 -I security/apk_gate.py snapshot --apk <apk>

Exit codes for ``check``: 0 pass, 1 violations found, 2 usage error.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"

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


def _android_attr(elem: ET.Element, name: str) -> str | None:
    return elem.get(f"{{{ANDROID_NS}}}{name}")


def _to_bool(value: str | None) -> bool | None:
    if value is None:
        return None
    return value == "true"


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
    allow_backup: bool | None = None
    debuggable: bool | None = None

    application = root.find("application")
    if application is not None:
        allow_backup = _to_bool(_android_attr(application, "allowBackup"))
        debuggable = _to_bool(_android_attr(application, "debuggable"))

        for tag in _COMPONENT_TAGS:
            for elem in application.findall(tag):
                name = _android_attr(elem, "name")
                exported = _android_attr(elem, "exported")
                if name and exported == "true":
                    exported_components.add(f"{tag}:{name}")

    return ManifestFacts(
        package=package,
        permissions=frozenset(permissions),
        exported_components=frozenset(exported_components),
        allow_backup=allow_backup,
        debuggable=debuggable,
    )


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

    return violations


def _facts_to_jsonable(facts: ManifestFacts) -> dict:
    return {
        "package": facts.package,
        "permissions": sorted(facts.permissions),
        "exported_components": sorted(facts.exported_components),
        "allow_backup": facts.allow_backup,
        "debuggable": facts.debuggable,
    }


def _run_apkanalyzer_manifest_print(apk_path: str) -> str:
    result = subprocess.run(
        ["apkanalyzer", "manifest", "print", apk_path],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"apkanalyzer manifest print failed (exit {result.returncode}): "
            f"{result.stderr.strip()}"
        )
    return result.stdout


def _load_manifest_text(args: argparse.Namespace) -> str:
    if args.xml:
        with open(args.xml, encoding="utf-8") as fh:
            return fh.read()
    return _run_apkanalyzer_manifest_print(args.apk)


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

    violations = check(facts, allowlist)
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

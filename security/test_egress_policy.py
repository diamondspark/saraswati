"""Policy tests for the Task 12 deny-by-default network egress allowlist.

Stdlib-only: parses `app/src/main/res/xml/network_security_config.xml`,
the Saraswati Updates app's `updater/src/main/res/xml/network_security_config.xml`
and `security/egress-allowlist.json` as text/XML, with no Gradle invocation, so
this suite stays fast and runs on any machine. Run with:

    python3 -I -m unittest discover -s security -p 'test_*.py' -v

These are static checks that the two files agree with each other and with
the forbidden-host list; they cannot prove the XML is actually *enforced* on
a device (an empty <trust-anchors/> could in principle be ignored by some
platform build) — that proof is
`app/src/androidTest/kotlin/com/metrolist/music/saraswati/EgressPolicyInstrumentedTest.kt`,
run on a device or the emulator.
"""

from __future__ import annotations

import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLOWLIST_JSON = ROOT / "security" / "egress-allowlist.json"
NETWORK_CONFIG_XML = ROOT / "app" / "src" / "main" / "res" / "xml" / "network_security_config.xml"
UPDATER_NETWORK_CONFIG_XML = (
    ROOT / "updater" / "src" / "main" / "res" / "xml" / "network_security_config.xml"
)

# Every network security config that ships in a Saraswati APK. The
# deny-by-default properties below must hold for each of them.
ALL_NETWORK_CONFIGS = (NETWORK_CONFIG_XML, UPDATER_NETWORK_CONFIG_XML)

# Each module's main manifest, which must actually point its <application>
# at that config: without android:networkSecurityConfig the platform default
# applies (system CAs trusted for every host) and the XML above is dead.
ANDROID_NS = "http://schemas.android.com/apk/res/android"
EXPECTED_NSC_REFERENCE = "@xml/network_security_config"
MANIFESTS = {
    "app": ROOT / "app" / "src" / "main" / "AndroidManifest.xml",
    "updater": ROOT / "updater" / "src" / "main" / "AndroidManifest.xml",
}

# The Updater's whole egress surface, per the phase 2 Global Constraints:
# github.com (release-asset host), githubusercontent.com and its subdomains
# (the CDN a release download redirects to), diamondspark.github.io (the
# manifest host). Nothing else.
UPDATER_ALLOWED = {
    ("github.com", False),
    ("githubusercontent.com", True),
    ("diamondspark.github.io", False),
}


def _load_allowlist() -> dict:
    with ALLOWLIST_JSON.open(encoding="utf-8") as f:
        return json.load(f)


def _load_xml_root(path: Path = NETWORK_CONFIG_XML) -> ET.Element:
    return ET.parse(path).getroot()


def _base_config(root: ET.Element) -> ET.Element:
    base = root.find("base-config")
    if base is None:
        raise AssertionError("network_security_config.xml has no <base-config>")
    return base


def _domain_configs(root: ET.Element) -> list[ET.Element]:
    """Every <domain-config> at any depth.

    The schema lets a <domain-config> nest child <domain-config>s, each with
    its own cleartextTrafficPermitted and <trust-anchors>; a top-level-only
    findall would let a cleartext or custom-CA override hide in one.
    """
    return list(root.iter("domain-config"))


def _xml_allowed_set(root: ET.Element) -> set[tuple[str, bool]]:
    """Return {(domain, includeSubdomains), ...} for every <domain> in the XML.

    A single domain-config may list more than one <domain>; each one is
    still one allowlist entry.
    """
    entries: set[tuple[str, bool]] = set()
    for domain_config in _domain_configs(root):
        for domain_el in domain_config.findall("domain"):
            name = (domain_el.text or "").strip()
            include_subdomains = domain_el.get("includeSubdomains", "false").strip().lower() == "true"
            entries.add((name, include_subdomains))
    return entries


def _is_covered(host: str, name: str, subdomains: bool) -> bool:
    """Mirror the brief's covering rule: with subdomains, `name` covers
    itself and anything ending in `.<name>`; without, only an exact match.
    """
    if host == name:
        return True
    return bool(subdomains) and host.endswith("." + name)


class NetworkSecurityConfigTests(unittest.TestCase):
    def test_base_config_trusts_nothing(self) -> None:
        for path in ALL_NETWORK_CONFIGS:
            with self.subTest(config=str(path.relative_to(ROOT))):
                base = _base_config(_load_xml_root(path))
                trust_anchors = base.find("trust-anchors")
                self.assertIsNotNone(
                    trust_anchors, "<base-config> must have a <trust-anchors> element"
                )
                certificates = trust_anchors.findall("certificates")
                self.assertEqual(
                    certificates,
                    [],
                    "base-config's <trust-anchors> must stay empty (no <certificates>) so any "
                    "host without its own domain-config fails the TLS handshake",
                )

    def test_no_user_added_cas_anywhere(self) -> None:
        for path in ALL_NETWORK_CONFIGS:
            with self.subTest(config=str(path.relative_to(ROOT))):
                sources = [
                    (cert.get("src") or "").strip().lower()
                    for cert in _load_xml_root(path).iter("certificates")
                ]
                self.assertNotIn(
                    "user",
                    sources,
                    "no <certificates src=\"user\"> anywhere: a user-installed CA must "
                    "never be able to intercept this app's TLS",
                )

    def test_domain_configs_trust_only_system_cas(self) -> None:
        for path in ALL_NETWORK_CONFIGS:
            with self.subTest(config=str(path.relative_to(ROOT))):
                for domain_config in _domain_configs(_load_xml_root(path)):
                    domains = [d.text for d in domain_config.findall("domain")]
                    for trust_anchors in domain_config.findall("trust-anchors"):
                        for cert in trust_anchors.findall("certificates"):
                            self.assertEqual(
                                (cert.get("src") or "").strip(),
                                "system",
                                f"domain-config for {domains} trusts certificates "
                                f"src={cert.get('src')!r}; only the system CA store is "
                                "allowed (no bundled or user CAs)",
                            )

    def test_cleartext_is_off_everywhere(self) -> None:
        for path in ALL_NETWORK_CONFIGS:
            with self.subTest(config=str(path.relative_to(ROOT))):
                root = _load_xml_root(path)
                base = _base_config(root)
                self.assertEqual(
                    base.get("cleartextTrafficPermitted"),
                    "false",
                    "base-config must set cleartextTrafficPermitted=\"false\"",
                )
                for domain_config in _domain_configs(root):
                    value = domain_config.get("cleartextTrafficPermitted")
                    self.assertNotEqual(
                        value,
                        "true",
                        f"domain-config for {[d.text for d in domain_config.findall('domain')]} "
                        "must not re-enable cleartext",
                    )

    def test_no_debug_overrides(self) -> None:
        for path in ALL_NETWORK_CONFIGS:
            with self.subTest(config=str(path.relative_to(ROOT))):
                self.assertEqual(
                    list(_load_xml_root(path).iter("debug-overrides")),
                    [],
                    "<debug-overrides> must not exist: it would widen trust (e.g. user CAs) "
                    "on any debuggable build",
                )

    def test_each_manifest_wires_in_its_network_security_config(self) -> None:
        for module, manifest in MANIFESTS.items():
            with self.subTest(module=module):
                application = ET.parse(manifest).getroot().find("application")
                self.assertIsNotNone(application, f"{module}: manifest has no <application>")
                self.assertEqual(
                    application.get(f"{{{ANDROID_NS}}}networkSecurityConfig"),
                    EXPECTED_NSC_REFERENCE,
                    f"{module}: <application> must set android:networkSecurityConfig="
                    f"\"{EXPECTED_NSC_REFERENCE}\", or the platform default (every "
                    "system CA, every host) applies instead",
                )

    def test_no_other_source_set_overrides_the_config(self) -> None:
        # A debug/flavor source set's own manifest attribute or
        # res/xml/network_security_config.xml would silently replace the
        # checked file for that variant.
        for module in MANIFESTS:
            src = ROOT / module / "src"
            with self.subTest(module=module):
                configs = sorted(
                    p.relative_to(ROOT).as_posix()
                    for p in src.glob("*/res/xml/network_security_config.xml")
                )
                self.assertEqual(
                    configs,
                    [f"{module}/src/main/res/xml/network_security_config.xml"],
                    "only the main source set may define network_security_config.xml",
                )
                overriding_manifests = sorted(
                    p.relative_to(ROOT).as_posix()
                    for p in src.glob("*/AndroidManifest.xml")
                    if p.parent.name != "main"
                    and "networkSecurityConfig" in p.read_text(encoding="utf-8")
                )
                self.assertEqual(
                    overriding_manifests,
                    [],
                    "no non-main manifest may set, replace or remove networkSecurityConfig",
                )

    def test_updater_allows_exactly_its_three_hosts(self) -> None:
        self.assertEqual(
            _xml_allowed_set(_load_xml_root(UPDATER_NETWORK_CONFIG_XML)),
            UPDATER_ALLOWED,
            "the Updater's network_security_config.xml must allow exactly github.com, "
            "githubusercontent.com (with subdomains) and diamondspark.github.io",
        )

    def test_xml_matches_the_allowlist_file(self) -> None:
        allowlist = _load_allowlist()
        expected = {(entry["name"], bool(entry["subdomains"])) for entry in allowlist["allowed"]}
        self.assertEqual(
            len(expected),
            len(allowlist["allowed"]),
            "egress-allowlist.json has a duplicate (name, subdomains) entry",
        )
        actual = _xml_allowed_set(_load_xml_root())
        self.assertEqual(
            actual,
            expected,
            "network_security_config.xml's <domain-config> entries must exactly match "
            "security/egress-allowlist.json's \"allowed\" list (same names, same "
            "includeSubdomains flags, nothing extra, nothing missing)",
        )

    def test_no_forbidden_host_is_covered(self) -> None:
        allowlist = _load_allowlist()
        allowed = [(entry["name"], bool(entry["subdomains"])) for entry in allowlist["allowed"]]
        forbidden_hosts = allowlist["forbidden_hosts"]
        self.assertTrue(forbidden_hosts, "egress-allowlist.json's forbidden_hosts must not be empty")
        for host in forbidden_hosts:
            covering = [
                name for name, subdomains in allowed if _is_covered(host, name, subdomains)
            ]
            self.assertEqual(
                covering,
                [],
                f"forbidden host {host!r} is covered by allowed entr{'y' if len(covering) == 1 else 'ies'} "
                f"{covering!r}; it must resolve to blocked",
            )


if __name__ == "__main__":
    unittest.main()

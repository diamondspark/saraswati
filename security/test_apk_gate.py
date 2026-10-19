"""Unit tests for apk_gate.py. Stdlib only; run with:

    python3 -I -m unittest discover -s security -p 'test_*.py' -v
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import apk_gate
from apk_gate import (
    check,
    check_hub_agreement,
    parse_hub_base_url,
    parse_manifest,
    parse_network_config_domains,
)

FIXTURES = Path(__file__).resolve().parent / "fixtures"
SECURITY = Path(__file__).resolve().parent
ROOT = SECURITY.parent

# A standalone allowlist for these unit tests only (independent of the real
# security/manifest-allowlist.json shipped for the app). RECORD_AUDIO is
# deliberately listed under both "permissions" and "forbidden_permissions"
# to prove forbidden_permissions wins even over a mistaken allowlist entry.
ALLOWLIST = {
    "package": "dev.diamondspark.saraswati",
    "permissions": [
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.WAKE_LOCK",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "dev.diamondspark.saraswati.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        "android.permission.RECORD_AUDIO",
    ],
    "exported_components": [
        "activity:com.metrolist.music.MainActivity",
        "activity-alias:com.metrolist.music.MainActivityAlias",
        "activity-alias:com.metrolist.music.MainActivityStatic",
        "service:com.metrolist.music.playback.MusicService",
        "receiver:androidx.media3.session.MediaButtonReceiver",
        "receiver:androidx.profileinstaller.ProfileInstallReceiver",
    ],
    "forbidden_permissions": [
        "android.permission.RECORD_AUDIO",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.QUERY_ALL_PACKAGES",
    ],
}


def _read_fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


AAPT2_RESOURCES = "aapt2_resources.txt"


def _resource_names() -> dict:
    """The id -> @type/name table of a release APK, from a trimmed `aapt2 dump resources`."""
    return apk_gate.parse_aapt2_resource_names(_read_fixture(AAPT2_RESOURCES))


def _pins(xml_text: str) -> dict:
    """What `apk_gate.py snapshot` prints for the pinned sections: the part of
    the allowlist a human copies from a reviewed release APK. References are
    pinned by resource name, with the raw ids kept as the fallback pin."""
    facts = parse_manifest(xml_text)
    snapshot = apk_gate._facts_to_jsonable(apk_gate.resolve_references(facts, _resource_names()))
    return {key: snapshot[key] for key in ("intent_filters", "queries", "references", "reference_ids")}


# clean_manifest.xml carries no intent filters, <queries> or references; its pins
# are the empty ones, so the pre-existing tests below still describe a clean pass.
ALLOWLIST.update(_pins(_read_fixture("clean_manifest.xml")))


class ParseManifestTests(unittest.TestCase):
    def test_parse_extracts_permissions_and_exported_components(self) -> None:
        facts = parse_manifest(_read_fixture("clean_manifest.xml"))

        self.assertEqual(facts.package, "dev.diamondspark.saraswati")
        self.assertEqual(
            facts.permissions,
            frozenset(
                {
                    "android.permission.INTERNET",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.WAKE_LOCK",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                    "dev.diamondspark.saraswati.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                }
            ),
        )
        self.assertEqual(
            facts.exported_components,
            frozenset(
                {
                    "activity:com.metrolist.music.MainActivity",
                    "activity-alias:com.metrolist.music.MainActivityAlias",
                    "activity-alias:com.metrolist.music.MainActivityStatic",
                    "service:com.metrolist.music.playback.MusicService",
                    "receiver:androidx.media3.session.MediaButtonReceiver",
                    "receiver:androidx.profileinstaller.ProfileInstallReceiver",
                }
            ),
        )
        self.assertIs(facts.allow_backup, False)
        self.assertIsNone(facts.debuggable)


class CheckTests(unittest.TestCase):
    def test_check_passes_clean_manifest(self) -> None:
        facts = parse_manifest(_read_fixture("clean_manifest.xml"))
        self.assertEqual([], check(facts, ALLOWLIST))

    def test_check_flags_unexpected_permission(self) -> None:
        facts = parse_manifest(_read_fixture("bad_manifest.xml"))
        violations = check(facts, ALLOWLIST)
        self.assertIn("unexpected permission: android.permission.READ_CONTACTS", violations)

    def test_check_flags_forbidden_permission_even_when_allowlisted(self) -> None:
        facts = parse_manifest(_read_fixture("bad_manifest.xml"))
        violations = check(facts, ALLOWLIST)
        # RECORD_AUDIO is listed in ALLOWLIST["permissions"] above, but it
        # must still be reported as forbidden, not merely unexpected.
        self.assertIn("forbidden permission: android.permission.RECORD_AUDIO", violations)
        self.assertNotIn("unexpected permission: android.permission.RECORD_AUDIO", violations)

    def test_check_flags_unexpected_exported_component(self) -> None:
        facts = parse_manifest(_read_fixture("bad_manifest.xml"))
        violations = check(facts, ALLOWLIST)
        self.assertIn(
            "unexpected exported component: receiver:com.metrolist.music.widget.MusicWidgetReceiver",
            violations,
        )

    def test_check_flags_allow_backup_true(self) -> None:
        facts = parse_manifest(_read_fixture("bad_manifest.xml"))
        violations = check(facts, ALLOWLIST)
        self.assertIn("allowBackup must be false", violations)

    def test_check_flags_package_mismatch(self) -> None:
        facts = parse_manifest(_read_fixture("bad_manifest.xml"))
        violations = check(facts, ALLOWLIST)
        self.assertIn(
            "package mismatch: expected dev.diamondspark.saraswati, found com.evil.fork.app",
            violations,
        )


# ---------------------------------------------------------------------------
# Intent filters, <queries> and resource references (fix wave A, I5)
# ---------------------------------------------------------------------------

MAIN = "activity:com.metrolist.music.MainActivity"
PAIR_FILTER_MARKER = 'android:path="/pair"'
LISTEN_TOGETHER_FILTER = """
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" />
                <data android:host="metrolist.cc" />
                <data android:pathPrefix="/listen" />
            </intent-filter>
"""


def _pinned_allowlist() -> dict:
    xml = _read_fixture("pinned_manifest.xml")
    return {
        **ALLOWLIST,
        "exported_components": sorted(parse_manifest(xml).exported_components),
        **_pins(xml),
    }


def _violations_after(edit) -> list[str]:
    """Check the pinned fixture, after [edit] changes its text, against its own pins."""
    xml = edit(_read_fixture("pinned_manifest.xml"))
    return check(parse_manifest(xml), _pinned_allowlist())


def _replace(old: str, new: str):
    def edit(xml: str) -> str:
        assert old in xml, f"fixture no longer contains {old!r}"
        return xml.replace(old, new, 1)

    return edit


class CanonicalFactsTests(unittest.TestCase):
    def setUp(self) -> None:
        self.facts = parse_manifest(_read_fixture("pinned_manifest.xml"))

    def test_data_elements_merge_into_one_set_per_attribute(self) -> None:
        pair = [f for f in self.facts.intent_filters[MAIN] if "/pair" in f["data"].get("path", [])]
        self.assertEqual(
            [
                {
                    "actions": ["android.intent.action.VIEW"],
                    "categories": ["android.intent.category.BROWSABLE", "android.intent.category.DEFAULT"],
                    "data": {"host": ["hub.example.workers.dev"], "path": ["/pair"], "scheme": ["https"]},
                    "autoVerify": True,
                }
            ],
            pair,
        )

    def test_filters_of_every_component_kind_are_recorded(self) -> None:
        self.assertEqual(
            {
                MAIN,
                "activity-alias:com.metrolist.music.MainActivityAlias",
                "service:com.metrolist.music.playback.MusicService",
                "service:com.metrolist.music.playback.ExoDownloadService",
                "receiver:androidx.media3.session.MediaButtonReceiver",
            },
            set(self.facts.intent_filters),
        )

    def test_queries_are_recorded(self) -> None:
        self.assertEqual(
            ["com.google.android.projection.gearhead", "dev.diamondspark.saraswati.updater"],
            self.facts.queries["packages"],
        )
        self.assertEqual(
            [["android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL"]],
            [i["actions"] for i in self.facts.queries["intents"]],
        )

    def test_application_references_are_recorded(self) -> None:
        self.assertEqual(
            {
                "dataExtractionRules": "@ref/0x7f150003",
                "fullBackupContent": "@ref/0x7f150002",
                "networkSecurityConfig": "@ref/0x7f150006",
            },
            self.facts.references,
        )

    def test_a_filter_without_an_export_attribute_makes_the_component_exported(self) -> None:
        xml = _replace(
            "</application>",
            '<service android:name="com.evil.Svc"><intent-filter><action android:name="x.y" /></intent-filter></service></application>',
        )(_read_fixture("pinned_manifest.xml"))

        self.assertIn("service:com.evil.Svc", parse_manifest(xml).exported_components)

    def test_how_data_is_spread_over_elements_or_listed_does_not_matter(self) -> None:
        merged = _replace(
            '<data android:scheme="https" />\n                <data android:host="hub.example.workers.dev" />\n'
            '                <data android:path="/pair" />',
            '<data android:path="/pair" android:host="hub.example.workers.dev" android:scheme="https" />',
        )
        reordered = _replace(
            '<category android:name="android.intent.category.DEFAULT" />\n'
            '                <category android:name="android.intent.category.BROWSABLE" />\n'
            '                <data android:scheme="https" />\n                <data android:host="hub.example.workers.dev" />',
            '<category android:name="android.intent.category.BROWSABLE" />\n'
            '                <category android:name="android.intent.category.DEFAULT" />\n'
            '                <data android:scheme="https" />\n                <data android:host="hub.example.workers.dev" />',
        )

        self.assertEqual([], _violations_after(merged))
        self.assertEqual([], _violations_after(reordered))


class PinnedManifestGateTests(unittest.TestCase):
    def assertViolation(self, prefix: str, violations: list[str]) -> None:
        self.assertTrue(
            any(v.startswith(prefix) for v in violations),
            f"no violation starting with {prefix!r} in {violations}",
        )

    def test_the_manifest_that_was_pinned_passes(self) -> None:
        self.assertEqual([], _violations_after(lambda xml: xml))

    def test_widening_the_pairing_path_to_a_prefix_fails(self) -> None:
        violations = _violations_after(_replace(PAIR_FILTER_MARKER, 'android:pathPrefix="/pair"'))

        self.assertViolation(f"unexpected intent-filter on {MAIN}", violations)
        self.assertViolation(f"missing intent-filter on {MAIN}", violations)
        self.assertTrue(any('"pathPrefix":["/pair"]' in v for v in violations))

    def test_widening_the_pairing_path_to_a_pattern_fails(self) -> None:
        violations = _violations_after(_replace(PAIR_FILTER_MARKER, 'android:pathPattern="/.*"'))

        self.assertViolation(f"unexpected intent-filter on {MAIN}", violations)

    def test_a_re_added_listen_together_filter_fails(self) -> None:
        violations = _violations_after(
            _replace('<intent-filter android:autoVerify="true">', LISTEN_TOGETHER_FILTER + '<intent-filter android:autoVerify="true">')
        )

        self.assertViolation(f"unexpected intent-filter on {MAIN}", violations)
        self.assertTrue(any("metrolist.cc" in v for v in violations))

    def test_a_new_exported_component_fails(self) -> None:
        violations = _violations_after(
            _replace("</application>", '<receiver android:name="com.evil.Receiver" android:exported="true" /></application>')
        )

        self.assertIn("unexpected exported component: receiver:com.evil.Receiver", violations)

    def test_a_new_component_with_a_filter_and_no_export_attribute_fails(self) -> None:
        violations = _violations_after(
            _replace(
                "</application>",
                '<service android:name="com.evil.Svc"><intent-filter><action android:name="x.y" /></intent-filter></service></application>',
            )
        )

        self.assertIn("unexpected exported component: service:com.evil.Svc", violations)
        self.assertViolation("unexpected intent-filter on service:com.evil.Svc", violations)

    def test_a_new_unexported_component_with_a_filter_still_fails(self) -> None:
        violations = _violations_after(
            _replace(
                "</application>",
                '<service android:name="com.evil.Svc" android:exported="false">'
                '<intent-filter><action android:name="x.y" /></intent-filter></service></application>',
            )
        )

        self.assertViolation("unexpected intent-filter on service:com.evil.Svc", violations)

    def test_each_kind_of_filter_drift_fails(self) -> None:
        drifts = {
            "autoVerify flipped": _replace('<intent-filter android:autoVerify="true">', "<intent-filter>"),
            "an extra action": _replace(
                '<action android:name="android.intent.action.SEND" />',
                '<action android:name="android.intent.action.SEND" /><action android:name="android.intent.action.SEND_MULTIPLE" />',
            ),
            "an extra category": _replace(
                '<action android:name="android.intent.action.MEDIA_BUTTON" />',
                '<action android:name="android.intent.action.MEDIA_BUTTON" /><category android:name="android.intent.category.BROWSABLE" />',
            ),
            "a wider mime type": _replace('android:mimeType="text/plain"', 'android:mimeType="*/*"'),
            "an added scheme": _replace(
                '<data android:host="hub.example.workers.dev" />',
                '<data android:host="hub.example.workers.dev" /><data android:scheme="http" />',
            ),
            "a wider host": _replace('android:host="music.youtube.com"', 'android:host="*.youtube.com"'),
            "a port": _replace(
                '<data android:host="hub.example.workers.dev" />',
                '<data android:host="hub.example.workers.dev" android:port="8080" />',
            ),
            "a higher priority": _replace(
                '<intent-filter android:autoVerify="true">',
                '<intent-filter android:autoVerify="true" android:priority="999">',
            ),
            "a filter on the service": _replace(
                '<action android:name="android.media.browse.MediaBrowserService" />',
                '<action android:name="android.media.browse.MediaBrowserService" /><action android:name="x.y" />',
            ),
        }
        for what, edit in drifts.items():
            with self.subTest(what):
                self.assertTrue(
                    any("intent-filter" in v for v in _violations_after(edit)),
                    f"{what} did not fail the gate",
                )

    def test_a_removed_filter_fails_so_a_dropped_deep_link_is_noticed(self) -> None:
        violations = _violations_after(_replace(PAIR_FILTER_MARKER, 'android:path="/gone"'))

        self.assertViolation(f"missing intent-filter on {MAIN}", violations)

    def test_queries_drift_fails(self) -> None:
        self.assertIn(
            "unexpected query package: com.bank.app",
            _violations_after(_replace("</queries>", '<package android:name="com.bank.app" /></queries>')),
        )
        self.assertIn(
            "missing query package: com.google.android.projection.gearhead",
            _violations_after(_replace('<package android:name="com.google.android.projection.gearhead" />', "")),
        )
        self.assertIn(
            "unexpected query provider: com.bank.provider",
            _violations_after(_replace("</queries>", '<provider android:authorities="com.bank.provider" /></queries>')),
        )
        self.assertViolation(
            "unexpected query intent",
            _violations_after(_replace("</queries>", '<intent><action android:name="android.intent.action.SEND" /></intent></queries>')),
        )

    def test_reference_drift_fails_and_explains_the_id_shift(self) -> None:
        violations = _violations_after(_replace('android:networkSecurityConfig="@ref/0x7f150006"', 'android:networkSecurityConfig="@ref/0x7f150007"'))

        self.assertIn("application networkSecurityConfig changed: pinned @ref/0x7f150006, found @ref/0x7f150007", violations)
        self.assertViolation("note: a raw @ref/0x... id shifts", violations)

    def test_a_removed_reference_fails(self) -> None:
        for attr in ("dataExtractionRules", "fullBackupContent", "networkSecurityConfig"):
            with self.subTest(attr):
                violations = _violations_after(lambda xml, a=attr: _strip_attribute(xml, a))
                self.assertTrue(any(f"application {attr} changed" in v and "found none" in v for v in violations), violations)

    def test_an_allowlist_without_the_pins_fails_closed(self) -> None:
        facts = parse_manifest(_read_fixture("pinned_manifest.xml"))
        bare = {k: v for k, v in _pinned_allowlist().items() if k not in ("intent_filters", "queries", "references")}

        violations = check(facts, bare)

        for key in ("intent_filters", "queries", "references"):
            self.assertViolation(f"allowlist has no {key} pin", violations)

    def test_raw_ids_without_a_reference_ids_pin_fail_closed(self) -> None:
        facts = parse_manifest(_read_fixture("pinned_manifest.xml"))
        allowlist = {k: v for k, v in _pinned_allowlist().items() if k != "reference_ids"}

        self.assertViolation("allowlist has no reference_ids pin", check(facts, allowlist))


# ---------------------------------------------------------------------------
# References are pinned by resource name, not by raw id (fix wave B1)
# ---------------------------------------------------------------------------

NAME_PINS = {
    "dataExtractionRules": "@xml/data_extraction_rules",
    "fullBackupContent": "@xml/backup_rules",
    "networkSecurityConfig": "@xml/network_security_config",
}


def _resolved_facts(edit=lambda xml: xml, table_text: str | None = None) -> apk_gate.ManifestFacts:
    names = apk_gate.parse_aapt2_resource_names(table_text if table_text is not None else _read_fixture(AAPT2_RESOURCES))
    return apk_gate.resolve_references(parse_manifest(edit(_read_fixture("pinned_manifest.xml"))), names)


def _shift_ids(text: str) -> str:
    """What adding an xml resource does to the ids after it: 0x7f150002 becomes 0x7f150012, and so on."""
    return text.replace("0x7f15000", "0x7f15001")


class Aapt2ResourceTableTests(unittest.TestCase):
    def test_ids_map_to_type_and_name(self) -> None:
        names = _resource_names()

        self.assertEqual("@xml/network_security_config", names["0x7f150006"])
        self.assertEqual("@xml/backup_rules", names["0x7f150002"])
        self.assertEqual("@anim/abc_popup_enter", names["0x7f010003"])

    def test_a_string_that_shares_a_name_with_an_xml_resource_stays_a_string(self) -> None:
        self.assertEqual("@string/network_security_config", _resource_names()["0x7f120001"])

    def test_value_and_file_lines_are_not_resources(self) -> None:
        names = _resource_names()

        self.assertEqual(11, len(names))
        self.assertTrue(all(v.startswith("@") and "/" in v for v in names.values()))

    def test_ids_are_lower_case_and_nothing_else_parses(self) -> None:
        self.assertEqual({"0x7f150006": "@xml/x"}, apk_gate.parse_aapt2_resource_names("  resource 0x7F150006 xml/x\n"))
        self.assertEqual({}, apk_gate.parse_aapt2_resource_names(""))
        self.assertEqual({}, apk_gate.parse_aapt2_resource_names("error: not an apk\n"))


class ReferenceByNameTests(unittest.TestCase):
    def assertViolation(self, prefix: str, violations: list[str]) -> None:
        self.assertTrue(any(v.startswith(prefix) for v in violations), f"no violation starting with {prefix!r} in {violations}")

    def test_raw_ids_resolve_to_names_and_the_ids_are_kept(self) -> None:
        facts = _resolved_facts()

        self.assertEqual(NAME_PINS, facts.references)
        self.assertEqual(
            {"dataExtractionRules": "@ref/0x7f150003", "fullBackupContent": "@ref/0x7f150002", "networkSecurityConfig": "@ref/0x7f150006"},
            facts.reference_ids,
        )
        self.assertTrue(facts.references_are_names)

    def test_the_pinned_manifest_passes_by_name(self) -> None:
        self.assertEqual([], check(_resolved_facts(), _pinned_allowlist()))

    def test_adding_an_xml_resource_shifts_the_ids_but_not_the_gate(self) -> None:
        shifted_manifest = lambda xml: _shift_ids(xml)  # noqa: E731
        shifted_table = _shift_ids(_read_fixture(AAPT2_RESOURCES))

        violations = check(_resolved_facts(shifted_manifest, shifted_table), _pinned_allowlist())

        self.assertEqual([], violations, "the ids moved, the names did not")

    def test_the_same_shift_does_fail_when_only_raw_ids_can_be_compared(self) -> None:
        facts = parse_manifest(_shift_ids(_read_fixture("pinned_manifest.xml")))

        violations = check(facts, _pinned_allowlist())

        self.assertViolation("application networkSecurityConfig changed: pinned @ref/0x7f150006, found @ref/0x7f150016", violations)

    def test_pointing_a_reference_at_a_different_resource_fails(self) -> None:
        facts = _resolved_facts(_replace('android:networkSecurityConfig="@ref/0x7f150006"', 'android:networkSecurityConfig="@ref/0x7f150008"'))

        violations = check(facts, _pinned_allowlist())

        self.assertIn("application networkSecurityConfig changed: pinned @xml/network_security_config, found @xml/provider_paths", violations)
        self.assertFalse(any(v.startswith("note: a raw") for v in violations), "no id-shift excuse when names were compared")

    def test_a_string_with_the_same_name_is_not_the_xml_resource(self) -> None:
        facts = _resolved_facts(_replace('android:networkSecurityConfig="@ref/0x7f150006"', 'android:networkSecurityConfig="@ref/0x7f120001"'))

        self.assertIn(
            "application networkSecurityConfig changed: pinned @xml/network_security_config, found @string/network_security_config",
            check(facts, _pinned_allowlist()),
        )

    def test_an_id_the_resource_table_does_not_hold_fails_and_says_why(self) -> None:
        facts = _resolved_facts(_replace('android:networkSecurityConfig="@ref/0x7f150006"', 'android:networkSecurityConfig="@ref/0x7f15ffff"'))

        violations = check(facts, _pinned_allowlist())

        self.assertIn("application networkSecurityConfig changed: pinned @xml/network_security_config, found @ref/0x7f15ffff", violations)
        self.assertViolation("note: @ref/0x7f15ffff is not in the APK's resource table", violations)

    def test_a_removed_reference_fails(self) -> None:
        for attr in NAME_PINS:
            with self.subTest(attr):
                violations = check(_resolved_facts(lambda xml, a=attr: _strip_attribute(xml, a)), _pinned_allowlist())
                self.assertTrue(any(f"application {attr} changed" in v and "found none" in v for v in violations), violations)

    def test_a_new_reference_fails(self) -> None:
        allowlist = _pinned_allowlist()
        del allowlist["references"]["fullBackupContent"]

        violations = check(_resolved_facts(), allowlist)

        self.assertIn("application fullBackupContent changed: pinned none, found @xml/backup_rules", violations)

    def test_the_name_pins_are_mandatory(self) -> None:
        allowlist = {k: v for k, v in _pinned_allowlist().items() if k != "references"}

        self.assertViolation("allowlist has no references pin", check(_resolved_facts(), allowlist))

    def test_the_raw_id_pins_are_not_consulted_when_names_were_compared(self) -> None:
        allowlist = _pinned_allowlist()
        allowlist["reference_ids"] = {"networkSecurityConfig": "@ref/0x7f150099"}

        self.assertEqual([], check(_resolved_facts(), allowlist))

    def test_a_source_manifest_that_names_its_resources_is_compared_by_name(self) -> None:
        source = lambda xml: (  # noqa: E731
            xml.replace("@ref/0x7f150002", "@xml/backup_rules")
            .replace("@ref/0x7f150003", "@xml/data_extraction_rules")
            .replace("@ref/0x7f150006", "@xml/network_security_config")
        )
        facts = parse_manifest(source(_read_fixture("pinned_manifest.xml")))

        self.assertTrue(facts.references_are_names)
        self.assertEqual([], check(facts, _pinned_allowlist()))
        drifted = parse_manifest(source(_read_fixture("pinned_manifest.xml")).replace("@xml/backup_rules", "@xml/other_rules"))
        self.assertIn("application fullBackupContent changed: pinned @xml/backup_rules, found @xml/other_rules", check(drifted, _pinned_allowlist()))


class FindAapt2Tests(unittest.TestCase):
    def _sdk(self, root: Path, versions: list[str], *, with_aapt2: bool = True) -> None:
        for version in versions:
            directory = root / "build-tools" / version
            directory.mkdir(parents=True)
            if with_aapt2:
                tool = directory / "aapt2"
                tool.write_text("#!/bin/sh\n", encoding="utf-8")
                tool.chmod(tool.stat().st_mode | stat.S_IXUSR)

    def test_the_newest_build_tools_under_android_home_win(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk = Path(tmp)
            self._sdk(sdk, ["34.0.0", "36.0.0", "9.0.0", "37.0.0"])

            found = apk_gate.find_aapt2({"ANDROID_HOME": str(sdk), "PATH": ""})

            self.assertEqual(str(sdk / "build-tools" / "37.0.0" / "aapt2"), found)

    def test_android_sdk_root_is_used_when_android_home_is_not_set(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk = Path(tmp)
            self._sdk(sdk, ["35.0.0"])

            found = apk_gate.find_aapt2({"ANDROID_SDK_ROOT": str(sdk), "PATH": ""})

            self.assertEqual(str(sdk / "build-tools" / "35.0.0" / "aapt2"), found)

    def test_an_sdk_without_the_tool_is_skipped_for_the_next_one(self) -> None:
        with tempfile.TemporaryDirectory() as empty, tempfile.TemporaryDirectory() as full:
            self._sdk(Path(empty), ["36.0.0"], with_aapt2=False)
            self._sdk(Path(full), ["36.0.0"])

            found = apk_gate.find_aapt2({"ANDROID_HOME": empty, "ANDROID_SDK_ROOT": full, "PATH": ""})

            self.assertEqual(str(Path(full) / "build-tools" / "36.0.0" / "aapt2"), found)

    def test_a_tool_on_path_is_the_last_resort(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            bin_dir = Path(tmp)
            tool = bin_dir / "aapt2"
            tool.write_text("#!/bin/sh\n", encoding="utf-8")
            tool.chmod(tool.stat().st_mode | stat.S_IXUSR)

            found = apk_gate.find_aapt2({"PATH": str(bin_dir)})

            self.assertEqual(str(tool), found)

    def test_nothing_found_is_none(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(apk_gate.find_aapt2({"ANDROID_HOME": tmp, "ANDROID_SDK_ROOT": str(Path(tmp) / "missing"), "PATH": ""}))
            self.assertIsNone(apk_gate.find_aapt2({}))


class ReferenceCliTests(unittest.TestCase):
    """`check --apk` end to end: names when aapt2 is there, a loud raw-id fallback when it is not."""

    def _check(self, *, manifest: str | None = None, aapt2: str | None = "/sdk/aapt2", table: str | None = None, aapt2_fails: bool = False):
        manifest = manifest if manifest is not None else _read_fixture("pinned_manifest.xml")
        resources = mock.Mock(side_effect=RuntimeError("aapt2 dump failed")) if aapt2_fails else mock.Mock(
            return_value=table if table is not None else _read_fixture(AAPT2_RESOURCES)
        )
        with tempfile.TemporaryDirectory() as tmp:
            allowlist = Path(tmp) / "allowlist.json"
            allowlist.write_text(json.dumps(_pinned_allowlist()), encoding="utf-8")
            out, err = io.StringIO(), io.StringIO()
            with mock.patch.object(apk_gate, "_run_apkanalyzer_manifest_print", return_value=manifest), \
                mock.patch.object(apk_gate, "find_aapt2", return_value=aapt2), \
                mock.patch.object(apk_gate, "_run_aapt2_resources", resources), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = apk_gate.main(["check", "--apk", "app.apk", "--allowlist", str(allowlist)])
        return code, out.getvalue(), err.getvalue(), resources

    def test_with_aapt2_the_references_are_compared_by_name_and_nothing_is_said(self) -> None:
        code, out, err, resources = self._check()

        self.assertEqual((0, "", ""), (code, out, err))
        resources.assert_called_once_with("/sdk/aapt2", "app.apk")

    def test_with_aapt2_a_shift_of_the_ids_passes(self) -> None:
        code, out, err, _ = self._check(manifest=_shift_ids(_read_fixture("pinned_manifest.xml")), table=_shift_ids(_read_fixture(AAPT2_RESOURCES)))

        self.assertEqual((0, "", ""), (code, out, err))

    def test_with_aapt2_a_different_resource_fails(self) -> None:
        manifest = _replace('android:networkSecurityConfig="@ref/0x7f150006"', 'android:networkSecurityConfig="@ref/0x7f150008"')(
            _read_fixture("pinned_manifest.xml")
        )

        code, out, _, _ = self._check(manifest=manifest)

        self.assertEqual(1, code)
        self.assertIn("pinned @xml/network_security_config, found @xml/provider_paths", out)

    def test_without_aapt2_the_same_raw_ids_pass_with_a_warning(self) -> None:
        code, out, err, resources = self._check(aapt2=None)

        self.assertEqual(0, code, "the pinned raw ids still match")
        self.assertEqual("", out)
        self.assertIn("warning: aapt2 not found", err)
        self.assertIn("raw resource ids", err)
        resources.assert_not_called()

    def test_without_aapt2_a_changed_raw_id_fails(self) -> None:
        manifest = _replace('android:networkSecurityConfig="@ref/0x7f150006"', 'android:networkSecurityConfig="@ref/0x7f150007"')(
            _read_fixture("pinned_manifest.xml")
        )

        code, out, err, _ = self._check(manifest=manifest, aapt2=None)

        self.assertEqual(1, code)
        self.assertIn("application networkSecurityConfig changed: pinned @ref/0x7f150006, found @ref/0x7f150007", out)
        self.assertIn("warning: aapt2 not found", err)

    def test_an_aapt2_that_fails_falls_back_the_same_way(self) -> None:
        code, out, err, _ = self._check(aapt2_fails=True)

        self.assertEqual((0, ""), (code, out))
        self.assertIn("warning: aapt2 failed", err)
        self.assertIn("raw resource ids", err)

    def test_an_aapt2_that_fails_still_fails_a_changed_raw_id(self) -> None:
        manifest = _replace('android:dataExtractionRules="@ref/0x7f150003"', 'android:dataExtractionRules="@ref/0x7f150004"')(
            _read_fixture("pinned_manifest.xml")
        )

        code, out, _, _ = self._check(manifest=manifest, aapt2_fails=True)

        self.assertEqual(1, code)
        self.assertIn("application dataExtractionRules changed: pinned @ref/0x7f150003, found @ref/0x7f150004", out)

    def test_a_source_manifest_never_needs_aapt2(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "AndroidManifest.xml"
            source.write_text(
                _read_fixture("pinned_manifest.xml")
                .replace("@ref/0x7f150002", "@xml/backup_rules")
                .replace("@ref/0x7f150003", "@xml/data_extraction_rules")
                .replace("@ref/0x7f150006", "@xml/network_security_config"),
                encoding="utf-8",
            )
            allowlist = Path(tmp) / "allowlist.json"
            allowlist.write_text(json.dumps(_pinned_allowlist()), encoding="utf-8")
            err = io.StringIO()
            with mock.patch.object(apk_gate, "find_aapt2", side_effect=AssertionError("aapt2 must not be looked for")), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
                code = apk_gate.main(["check", "--xml", str(source), "--allowlist", str(allowlist)])

        self.assertEqual((0, ""), (code, err.getvalue()))


def _strip_attribute(xml: str, attr: str) -> str:
    import re

    return re.sub(rf'\s+android:{attr}="[^"]*"', "", xml)


class RealAllowlistTests(unittest.TestCase):
    """The shipped pins, read from the repository, are well-formed and pin what matters."""

    def setUp(self) -> None:
        self.allowlist = json.loads((SECURITY / "manifest-allowlist.json").read_text(encoding="utf-8"))

    def test_the_pins_are_present(self) -> None:
        for key in ("intent_filters", "queries", "references", "reference_ids", "hub_host_agreement"):
            self.assertIn(key, self.allowlist)

    def test_references_are_pinned_by_resource_name_with_the_raw_ids_as_the_fallback(self) -> None:
        for name in ("manifest-allowlist.json", "updater-allowlist.json"):
            with self.subTest(name):
                allowlist = json.loads((SECURITY / name).read_text(encoding="utf-8"))
                self.assertTrue(allowlist["references"], "something is pinned")
                for attr, value in allowlist["references"].items():
                    self.assertRegex(value, r"^@xml/[a-z0-9_]+$", attr)
                self.assertEqual(set(allowlist["references"]), set(allowlist["reference_ids"]))
                for attr, value in allowlist["reference_ids"].items():
                    self.assertRegex(value, r"^@ref/0x[0-9a-f]{8}$", attr)

    def test_the_pairing_link_is_pinned_as_an_exact_https_path_on_one_host(self) -> None:
        pair = [
            f
            for f in self.allowlist["intent_filters"][MAIN]
            if "/pair" in f["data"].get("path", [])
        ]
        self.assertEqual(1, len(pair))
        self.assertEqual(["https"], pair[0]["data"]["scheme"])
        self.assertEqual(1, len(pair[0]["data"]["host"]))
        self.assertNotIn("pathPrefix", pair[0]["data"])
        self.assertNotIn("pathPattern", pair[0]["data"])

    def test_no_listen_together_deep_link_is_pinned(self) -> None:
        self.assertNotIn("metrolist.cc", json.dumps(self.allowlist["intent_filters"]))

    def test_every_pinned_filter_belongs_to_a_pinned_exported_component_or_a_known_unexported_one(self) -> None:
        exported = set(self.allowlist["exported_components"])
        unexported_with_filters = {o for o in self.allowlist["intent_filters"] if o not in exported}
        self.assertEqual({"service:com.metrolist.music.playback.ExoDownloadService"}, unexported_with_filters)


# ---------------------------------------------------------------------------
# The hub host agrees everywhere (fix wave A, I5)
# ---------------------------------------------------------------------------

HUB_HOST = "hub.example.workers.dev"
GRADLE = r"""
android {
    val gmsHubBaseUrl = "https://hub.example.workers.dev"
    productFlavors {
        getByName("foss") {
            buildConfigField("String", "HUB_BASE_URL", "\"\"")
        }
        getByName("gms") {
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "false")
            buildConfigField("String", "HUB_BASE_URL", "\"$gmsHubBaseUrl\"")
            manifestPlaceholders["hubHost"] = gmsHubBaseUrl.substringAfter("://").substringBefore("/")
        }
        getByName("izzy") {
            buildConfigField("String", "HUB_BASE_URL", "\"\"")
        }
    }
}
"""
NSC = """<network-security-config>
  <base-config cleartextTrafficPermitted="false"><trust-anchors/></base-config>
  <domain-config><domain includeSubdomains="true">youtube.com</domain></domain-config>
  <domain-config><domain includeSubdomains="false">
      hub.example.workers.dev
  </domain></domain-config>
</network-security-config>"""
EGRESS = {
    "allowed": [
        {"name": "youtube.com", "subdomains": True},
        {"name": "hub.example.workers.dev", "subdomains": False},
    ]
}
SECTION = {"flavor": "gms", "pairing_path": "/pair"}


def _agreement(
    *,
    gradle: str = GRADLE,
    nsc: str = NSC,
    egress: dict = EGRESS,
    manifest_edit=lambda xml: xml,
) -> list[str]:
    facts = parse_manifest(manifest_edit(_read_fixture("pinned_manifest.xml")))
    return check_hub_agreement(facts, SECTION, gradle_text=gradle, nsc_xml_text=nsc, egress_allowlist=egress)


class HubHostAgreementTests(unittest.TestCase):
    def test_agreement_passes(self) -> None:
        self.assertEqual([], _agreement())

    def test_the_gradle_url_is_read_for_the_gms_flavor_only(self) -> None:
        self.assertEqual("https://hub.example.workers.dev", parse_hub_base_url(GRADLE, "gms"))
        self.assertEqual("", parse_hub_base_url(GRADLE, "foss"))

    def test_a_literal_url_is_read_too(self) -> None:
        gradle = GRADLE.replace('\\"$gmsHubBaseUrl\\"', '\\"https://other.example\\"')
        self.assertEqual("https://other.example", parse_hub_base_url(gradle, "gms"))

    def test_a_different_gradle_host_fails(self) -> None:
        violations = _agreement(gradle=GRADLE.replace("hub.example.workers.dev", "elsewhere.example.workers.dev"))

        self.assertTrue(any("pairing-link host" in v for v in violations), violations)
        self.assertTrue(any("network_security_config" in v for v in violations), violations)
        self.assertTrue(any("egress-allowlist.json" in v for v in violations), violations)

    def test_a_different_pairing_link_host_fails(self) -> None:
        violations = _agreement(manifest_edit=_replace(f'android:host="{HUB_HOST}"', 'android:host="evil.example"'))

        self.assertEqual(
            [
                "hub host mismatch: HUB_BASE_URL host is hub.example.workers.dev, "
                "the manifest's pairing-link host is evil.example"
            ],
            violations,
        )

    def test_a_second_pairing_link_host_fails(self) -> None:
        violations = _agreement(
            manifest_edit=_replace(
                f'<data android:host="{HUB_HOST}" />',
                f'<data android:host="{HUB_HOST}" /><data android:host="evil.example" />',
            )
        )

        self.assertTrue(any("pairing-link host" in v for v in violations), violations)

    def test_no_pairing_link_filter_fails(self) -> None:
        violations = _agreement(manifest_edit=_replace(PAIR_FILTER_MARKER, 'android:path="/other"'))

        self.assertEqual(["hub host: no intent filter for the pairing link path /pair"], violations)

    def test_a_missing_network_config_entry_fails(self) -> None:
        violations = _agreement(nsc=NSC.replace("hub.example.workers.dev", "other.example"))

        self.assertEqual(1, len(violations))
        self.assertIn("network_security_config", violations[0])

    def test_a_subdomain_wildcard_network_config_entry_is_not_agreement(self) -> None:
        violations = _agreement(nsc=NSC.replace('includeSubdomains="false"', 'includeSubdomains="true"'))

        self.assertEqual(1, len(violations))
        self.assertIn("network_security_config", violations[0])

    def test_a_missing_egress_entry_fails(self) -> None:
        egress = {"allowed": [{"name": "youtube.com", "subdomains": True}]}

        violations = _agreement(egress=egress)

        self.assertEqual(["hub host mismatch: egress-allowlist.json has no exact entry for hub.example.workers.dev"], violations)

    def test_a_subdomain_wildcard_egress_entry_is_not_agreement(self) -> None:
        egress = {"allowed": [{"name": HUB_HOST, "subdomains": True}]}

        self.assertTrue(any("egress-allowlist.json" in v for v in _agreement(egress=egress)))

    def test_an_unreadable_gradle_file_fails_instead_of_passing(self) -> None:
        for broken in ("", GRADLE.replace('getByName("gms")', 'getByName("other")'), GRADLE.replace("HUB_BASE_URL", "HUB_URL")):
            with self.subTest(broken[:40]):
                violations = _agreement(gradle=broken)
                self.assertEqual(1, len(violations))
                self.assertTrue(violations[0].startswith("hub host:"))

    def test_a_url_with_a_path_or_port_or_plain_http_is_refused(self) -> None:
        for url in ("http://hub.example.workers.dev", "https://hub.example.workers.dev/api", "https://hub.example.workers.dev:8443"):
            with self.subTest(url):
                violations = _agreement(gradle=GRADLE.replace("https://hub.example.workers.dev", url))
                self.assertEqual(1, len(violations))
                self.assertIn("is not a bare https URL", violations[0])

    def test_network_config_domains_are_read_with_their_flags(self) -> None:
        self.assertEqual(
            [("youtube.com", True), ("hub.example.workers.dev", False)],
            parse_network_config_domains(NSC),
        )

    def test_the_repository_files_agree_with_each_other(self) -> None:
        # The gradle file, the source network security config and the egress allowlist
        # as committed. The manifest side is covered by the pins and by the release gate.
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        nsc = (ROOT / "app" / "src" / "main" / "res" / "xml" / "network_security_config.xml").read_text(encoding="utf-8")
        egress = json.loads((SECURITY / "egress-allowlist.json").read_text(encoding="utf-8"))
        host = apk_gate._hub_host_of(parse_hub_base_url(gradle, "gms"))

        self.assertIsNotNone(host)
        self.assertIn((host, False), parse_network_config_domains(nsc))
        self.assertIn((host, False), {(e["name"], e["subdomains"]) for e in egress["allowed"]})


def _main(argv: list[str]) -> int:
    """apk_gate.main with its violation listing kept out of the test output."""
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return apk_gate.main(argv)


class CliTests(unittest.TestCase):
    """The existing CLI, end to end on a fixture, with the new sections wired in."""

    def _run(self, allowlist: dict, xml_name: str = "pinned_manifest.xml", gradle: str = GRADLE) -> int:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            (tmp_path / "allowlist.json").write_text(json.dumps(allowlist), encoding="utf-8")
            (tmp_path / "build.gradle.kts").write_text(gradle, encoding="utf-8")
            (tmp_path / "nsc.xml").write_text(NSC, encoding="utf-8")
            (tmp_path / "egress.json").write_text(json.dumps(EGRESS), encoding="utf-8")
            return _main(
                ["check", "--xml", str(FIXTURES / xml_name), "--allowlist", str(tmp_path / "allowlist.json")]
            )

    def test_exit_codes_for_the_pins(self) -> None:
        self.assertEqual(0, self._run(_pinned_allowlist()))
        broken = _pinned_allowlist()
        broken["intent_filters"][MAIN] = broken["intent_filters"][MAIN][:-1]
        self.assertEqual(1, self._run(broken))

    def test_the_hub_agreement_runs_when_the_allowlist_asks_for_it(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            (tmp_path / "build.gradle.kts").write_text(GRADLE, encoding="utf-8")
            (tmp_path / "nsc.xml").write_text(NSC, encoding="utf-8")
            (tmp_path / "egress.json").write_text(json.dumps(EGRESS), encoding="utf-8")
            section = {
                "build_gradle": str(tmp_path / "build.gradle.kts"),
                "network_security_config": str(tmp_path / "nsc.xml"),
                "egress_allowlist": str(tmp_path / "egress.json"),
            }
            (tmp_path / "allowlist.json").write_text(
                json.dumps({**_pinned_allowlist(), "hub_host_agreement": section}), encoding="utf-8"
            )
            args = ["check", "--xml", str(FIXTURES / "pinned_manifest.xml"), "--allowlist", str(tmp_path / "allowlist.json")]
            self.assertEqual(0, _main(args))

            (tmp_path / "egress.json").write_text(json.dumps({"allowed": []}), encoding="utf-8")
            self.assertEqual(1, _main(args))

            (tmp_path / "egress.json").unlink()
            self.assertEqual(2, _main(args))

    def test_the_network_config_comes_from_the_apk_when_one_is_given(self) -> None:
        with mock.patch.object(apk_gate, "_run_apkanalyzer_manifest_print", return_value=_read_fixture("pinned_manifest.xml")), \
            mock.patch.object(apk_gate, "_read_apk_network_config", return_value=NSC.replace(HUB_HOST, "other.example")) as nsc:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                (tmp_path / "build.gradle.kts").write_text(GRADLE, encoding="utf-8")
                (tmp_path / "egress.json").write_text(json.dumps(EGRESS), encoding="utf-8")
                section = {
                    "build_gradle": str(tmp_path / "build.gradle.kts"),
                    "egress_allowlist": str(tmp_path / "egress.json"),
                }
                (tmp_path / "allowlist.json").write_text(
                    json.dumps({**_pinned_allowlist(), "hub_host_agreement": section}), encoding="utf-8"
                )

                code = _main(["check", "--apk", "app.apk", "--allowlist", str(tmp_path / "allowlist.json")])

            nsc.assert_called_once_with("app.apk")
            self.assertEqual(1, code)

    def test_snapshot_prints_the_pinned_sections(self) -> None:
        with mock.patch.object(apk_gate, "_run_apkanalyzer_manifest_print", return_value=_read_fixture("pinned_manifest.xml")), \
            mock.patch.object(apk_gate, "find_aapt2", return_value="/sdk/aapt2"), \
            mock.patch.object(apk_gate, "_run_aapt2_resources", return_value=_read_fixture(AAPT2_RESOURCES)):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(0, apk_gate.main(["snapshot", "--apk", "app.apk"]))

        snapshot = json.loads(out.getvalue())
        pins = _pins(_read_fixture("pinned_manifest.xml"))
        self.assertEqual(pins, {k: snapshot[k] for k in ("intent_filters", "queries", "references", "reference_ids")})
        self.assertEqual("@xml/network_security_config", snapshot["references"]["networkSecurityConfig"])
        self.assertEqual("@ref/0x7f150006", snapshot["reference_ids"]["networkSecurityConfig"])

    def test_snapshot_without_aapt2_prints_the_ids_only_and_says_names_are_missing(self) -> None:
        with mock.patch.object(apk_gate, "_run_apkanalyzer_manifest_print", return_value=_read_fixture("pinned_manifest.xml")), \
            mock.patch.object(apk_gate, "find_aapt2", return_value=None):
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(0, apk_gate.main(["snapshot", "--apk", "app.apk"]))

        snapshot = json.loads(out.getvalue())
        self.assertNotIn("references", snapshot, "raw ids must never be copied into the name pins")
        self.assertEqual("@ref/0x7f150006", snapshot["reference_ids"]["networkSecurityConfig"])
        self.assertIn("aapt2", err.getvalue())


if __name__ == "__main__":
    unittest.main()

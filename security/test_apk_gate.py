"""Unit tests for apk_gate.py. Stdlib only; run with:

    python3 -I -m unittest discover -s security -p 'test_*.py' -v
"""

from __future__ import annotations

import unittest
from pathlib import Path

from apk_gate import check, parse_manifest

FIXTURES = Path(__file__).resolve().parent / "fixtures"

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


if __name__ == "__main__":
    unittest.main()

"""Unit tests for make_manifest.py. Stdlib only; run with:

    python3 -I -m unittest discover -s scripts -p 'test_*.py' -v

`build_manifest` is the Python half of the update-manifest schema whose
Kotlin half is `update-core`'s `ManifestParser`/`DownloadPolicy` (see
security/fixtures/manifest_stable.json, the golden fixture both sides share).
A manifest this builder happily produces but the client strictly rejects (or,
worse, accepts something it shouldn't) is exactly the kind of drift that
turns a signed release into a bricked or hijacked update, so every rejection
path mirrored from the Kotlin parser is tested here too.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from make_manifest import build_manifest, main

REPO_ROOT = Path(__file__).resolve().parent.parent
GOLDEN_MANIFEST_PATH = REPO_ROOT / "security" / "fixtures" / "manifest_stable.json"

VALID_APK_URL = "https://github.com/diamondspark/saraswati/releases/download/v1.0.0/saraswati-v1.0.0.apk"
VALID_SHA256 = "a" * 64


def _valid_kwargs(**overrides: object) -> dict:
    kwargs: dict = {
        "channel": "stable",
        "version_code": 100,
        "version_name": "1.0.0",
        "apk_url": VALID_APK_URL,
        "apk_sha256": VALID_SHA256,
        "apk_size": 1_000_000,
        "urgent": False,
        "notes": "Routine update.",
        "published_at": "2026-10-17T00:00:00Z",
    }
    kwargs.update(overrides)
    return kwargs


class BuildManifestGoldenTests(unittest.TestCase):
    def test_output_equals_the_golden_file_for_fixed_inputs(self) -> None:
        golden = json.loads(GOLDEN_MANIFEST_PATH.read_text(encoding="utf-8"))

        manifest = build_manifest(
            channel="stable",
            version_code=4200,
            version_name="1.4.2",
            apk_url="https://github.com/diamondspark/saraswati/releases/download/v1.4.2/saraswati-v1.4.2-arm64.apk",
            apk_sha256="08c5119c42cdf51b5a5ac9a3415d9f24f9c7ea2952af1ff05eb6d9ecfb1f1483",
            apk_size=52_428_800,
            urgent=False,
            notes="Bug fixes and performance improvements.",
            published_at="2026-10-17T00:00:00Z",
            updater={
                "versionCode": 7,
                "apk": {
                    "url": "https://github.com/diamondspark/saraswati/releases/download/updater-v1.0.7/saraswati-updater-v1.0.7.apk",
                    "sha256": "b94853eb10c29951f088dce3d06f939e184b3310757c0b498a02ec30b53e3223",
                    "size": 10_485_760,
                },
            },
        )

        self.assertEqual(manifest, golden)

    def test_output_has_no_updater_key_when_updater_is_none(self) -> None:
        manifest = build_manifest(**_valid_kwargs())
        self.assertNotIn("updater", manifest)

    def test_schema_is_always_1(self) -> None:
        manifest = build_manifest(**_valid_kwargs())
        self.assertEqual(manifest["schema"], 1)


class BuildManifestValidationTests(unittest.TestCase):
    def test_rejects_an_unknown_channel(self) -> None:
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(channel="nightly"))

    def test_rejects_a_url_outside_the_release_prefix(self) -> None:
        other_repo = "https://github.com/someone-else/other-repo/releases/download/v1.0.0/x.apk"
        other_host = "https://evil.example/saraswati-v1.0.0.apk"
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(apk_url=other_repo))
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(apk_url=other_host))

    def test_rejects_http_url(self) -> None:
        http_url = "http://github.com/diamondspark/saraswati/releases/download/v1.0.0/x.apk"
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(apk_url=http_url))

    def test_rejects_a_dot_segment_path_traversal_out_of_the_release_prefix(self) -> None:
        # A literal startsWith(RELEASE_URL_PREFIX) check passes this
        # character-for-character, but a real HTTP client resolves the ".."
        # segments to a different repository entirely (see DownloadPolicy.kt
        # and ManifestParserTest's equivalent case on the Kotlin side).
        traversal_url = (
            "https://github.com/diamondspark/saraswati/releases/download/"
            "../../../../attacker/repo/releases/download/v1/evil.apk"
        )
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(apk_url=traversal_url))

    def test_rejects_notes_above_1000_chars(self) -> None:
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(notes="x" * 1001))

    def test_accepts_notes_at_exactly_1000_chars(self) -> None:
        manifest = build_manifest(**_valid_kwargs(notes="x" * 1000))
        self.assertEqual(len(manifest["notes"]), 1000)

    def test_counts_notes_in_utf16_units_like_kotlin(self) -> None:
        # U+1F3B5 (musical note emoji) is one code point but a UTF-16
        # surrogate pair: Kotlin's String.length (what ManifestParser
        # checks) counts it as 2. 998 + 2 = 1000 units: accepted.
        at_limit = "x" * 998 + "\U0001F3B5"
        self.assertEqual(len(at_limit), 999)
        build_manifest(**_valid_kwargs(notes=at_limit))
        # 999 + 2 = 1001 units, though Python's len() is only 1000: the
        # client would reject it, so the publisher must too.
        over_limit = "x" * 999 + "\U0001F3B5"
        self.assertEqual(len(over_limit), 1000)
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(notes=over_limit))

    def test_rejects_a_sha256_that_is_not_64_lowercase_hex(self) -> None:
        too_short = "a" * 63
        too_long = "a" * 65
        uppercase = "A" * 64
        non_hex = "g" * 64
        for bad_sha in (too_short, too_long, uppercase, non_hex):
            with self.assertRaises(ValueError):
                build_manifest(**_valid_kwargs(apk_sha256=bad_sha))

    def test_rejects_a_non_positive_version_code(self) -> None:
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(version_code=0))
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(version_code=-5))

    def test_rejects_size_above_100_mib(self) -> None:
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(apk_size=104_857_600 + 1))

    def test_rejects_a_non_positive_size(self) -> None:
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(apk_size=0))
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(apk_size=-1))


class BuildManifestUpdaterValidationTests(unittest.TestCase):
    def _valid_updater(self, **overrides: object) -> dict:
        updater = {
            "versionCode": 7,
            "apk": {
                "url": "https://github.com/diamondspark/saraswati/releases/download/updater-v1.0.7/x.apk",
                "sha256": "b" * 64,
                "size": 1_000_000,
            },
        }
        updater.update(overrides)
        return updater

    def test_rejects_an_updater_asset_outside_the_release_prefix(self) -> None:
        bad_updater = self._valid_updater(
            apk={"url": "https://evil.example/updater.apk", "sha256": "b" * 64, "size": 1000},
        )
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(updater=bad_updater))

    def test_rejects_a_non_positive_updater_version_code(self) -> None:
        bad_updater = self._valid_updater(versionCode=0)
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(updater=bad_updater))

    def test_rejects_an_updater_sha256_that_is_not_64_lowercase_hex(self) -> None:
        bad_updater = self._valid_updater(
            apk={
                "url": "https://github.com/diamondspark/saraswati/releases/download/updater-v1.0.7/x.apk",
                "sha256": "B" * 64,
                "size": 1_000_000,
            },
        )
        with self.assertRaises(ValueError):
            build_manifest(**_valid_kwargs(updater=bad_updater))

    def test_accepts_a_valid_updater(self) -> None:
        manifest = build_manifest(**_valid_kwargs(updater=self._valid_updater()))
        self.assertEqual(manifest["updater"]["versionCode"], 7)


class MainCliTests(unittest.TestCase):
    def test_cli_writes_the_manifest_json_to_the_given_path(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "beta.json"
            exit_code = main(
                [
                    "--channel", "beta",
                    "--version-code", "100",
                    "--version-name", "1.0.0",
                    "--apk-url", VALID_APK_URL,
                    "--apk-sha256", VALID_SHA256,
                    "--apk-size", "1000000",
                    "--notes", "Routine update.",
                    "--published-at", "2026-10-17T00:00:00Z",
                    "--out", str(out_path),
                ]
            )

            self.assertEqual(exit_code, 0)
            written = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertEqual(written["channel"], "beta")
            self.assertEqual(written["versionCode"], 100)
            self.assertNotIn("updater", written)

    def test_cli_defaults_urgent_to_false_and_notes_to_empty(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "beta.json"
            exit_code = main(
                [
                    "--channel", "beta",
                    "--version-code", "100",
                    "--version-name", "1.0.0",
                    "--apk-url", VALID_APK_URL,
                    "--apk-sha256", VALID_SHA256,
                    "--apk-size", "1000000",
                    "--published-at", "2026-10-17T00:00:00Z",
                    "--out", str(out_path),
                ]
            )

            self.assertEqual(exit_code, 0)
            written = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertEqual(written["urgent"], False)
            self.assertEqual(written["notes"], "")

    def test_cli_exits_1_on_validation_failure_and_writes_nothing(self) -> None:
        # A malformed sha256 is rejected by build_manifest itself (exit 1),
        # unlike an unknown --channel, which argparse's own `choices` would
        # reject before build_manifest ever runs (exit 2, covered by
        # test_cli_rejects_partial_updater_flags's sibling case below).
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "beta.json"
            exit_code = main(
                [
                    "--channel", "beta",
                    "--version-code", "100",
                    "--version-name", "1.0.0",
                    "--apk-url", VALID_APK_URL,
                    "--apk-sha256", "not-a-valid-sha",
                    "--apk-size", "1000000",
                    "--published-at", "2026-10-17T00:00:00Z",
                    "--out", str(out_path),
                ]
            )

            self.assertEqual(exit_code, 1)
            self.assertFalse(out_path.exists())

    def test_cli_builds_an_updater_section_when_all_four_updater_flags_are_given(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "beta.json"
            exit_code = main(
                [
                    "--channel", "beta",
                    "--version-code", "100",
                    "--version-name", "1.0.0",
                    "--apk-url", VALID_APK_URL,
                    "--apk-sha256", VALID_SHA256,
                    "--apk-size", "1000000",
                    "--published-at", "2026-10-17T00:00:00Z",
                    "--updater-version-code", "7",
                    "--updater-apk-url",
                    "https://github.com/diamondspark/saraswati/releases/download/updater-v1.0.7/x.apk",
                    "--updater-apk-sha256", "b" * 64,
                    "--updater-apk-size", "1000000",
                    "--out", str(out_path),
                ]
            )

            self.assertEqual(exit_code, 0)
            written = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertEqual(written["updater"]["versionCode"], 7)

    def test_cli_rejects_partial_updater_flags(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "beta.json"
            exit_code = main(
                [
                    "--channel", "beta",
                    "--version-code", "100",
                    "--version-name", "1.0.0",
                    "--apk-url", VALID_APK_URL,
                    "--apk-sha256", VALID_SHA256,
                    "--apk-size", "1000000",
                    "--published-at", "2026-10-17T00:00:00Z",
                    "--updater-version-code", "7",
                    "--out", str(out_path),
                ]
            )

            self.assertEqual(exit_code, 2)
            self.assertFalse(out_path.exists())


if __name__ == "__main__":
    unittest.main()

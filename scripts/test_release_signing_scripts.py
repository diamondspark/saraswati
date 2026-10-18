"""Tests for scripts/sign_release.sh and scripts/verify_apk_signer.sh.

Runs both scripts against fake `zipalign`/`apksigner` executables put first
on PATH, so no Android SDK, keystore or APK is needed. The real tools are
exercised end to end by saraswati-ci.yml's `release-dry-run` job.
"""

from __future__ import annotations

import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
SIGN = SCRIPTS / "sign_release.sh"
VERIFY = SCRIPTS / "verify_apk_signer.sh"

DIGEST = "ab" * 32
OTHER_DIGEST = "cd" * 32

# The two apksigner --print-certs formats seen in practice: build-tools
# 37.x labels the line with the scheme ("V3.0 Signer:"), 36.x does not.
BUILD_TOOLS_37_OUTPUT = (
    f"V3.0 Signer: certificate DN: CN=Saraswati Family\n"
    f"V3.0 Signer: certificate SHA-256 digest: {DIGEST}\n"
    f"V3.0 Signer: certificate SHA-1 digest: {'ef' * 20}\n"
    f"V3.0 Signer: public key SHA-256 digest: {OTHER_DIGEST}\n"
)
BUILD_TOOLS_36_OUTPUT = (
    f"Signer #1 certificate DN: CN=Saraswati Family\n"
    f"Signer #1 certificate SHA-256 digest: {DIGEST}\n"
    f"Signer #1 certificate SHA-1 digest: {'ef' * 20}\n"
    f"Signer #1 public key SHA-256 digest: {OTHER_DIGEST}\n"
)


def _write_exe(path: Path, body: str) -> None:
    path.write_text("#!/usr/bin/env bash\n" + body, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


class _FakeToolsCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.env = dict(os.environ)
        self.env["PATH"] = f"{self.bin}{os.pathsep}{self.env.get('PATH', '')}"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_script(self, *args: str | Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", *map(str, args)],
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
        )


class VerifyApkSignerTest(_FakeToolsCase):
    def _fake_apksigner(self, output: str) -> None:
        out_file = self.tmp / "apksigner-output.txt"
        out_file.write_text(output, encoding="utf-8")
        _write_exe(self.bin / "apksigner", f'cat "{out_file}"\n')

    def _apk(self) -> Path:
        apk = self.tmp / "app.apk"
        apk.write_bytes(b"not really an apk")
        return apk

    def test_matches_build_tools_37_format(self) -> None:
        self._fake_apksigner(BUILD_TOOLS_37_OUTPUT)
        result = self.run_script(VERIFY, "--expected", DIGEST, self._apk())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("signer verified", result.stdout)

    def test_matches_build_tools_36_format(self) -> None:
        self._fake_apksigner(BUILD_TOOLS_36_OUTPUT)
        result = self.run_script(VERIFY, "--expected", DIGEST, self._apk())
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_expected_digest_is_case_and_whitespace_insensitive(self) -> None:
        self._fake_apksigner(BUILD_TOOLS_37_OUTPUT)
        result = self.run_script(VERIFY, "--expected", f" {DIGEST.upper()}\n", self._apk())
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_mismatch_exits_1(self) -> None:
        for output in (BUILD_TOOLS_37_OUTPUT, BUILD_TOOLS_36_OUTPUT):
            with self.subTest(output=output.splitlines()[0]):
                self._fake_apksigner(output)
                result = self.run_script(VERIFY, "--expected", OTHER_DIGEST, self._apk())
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn("signer mismatch", result.stderr)

    def test_unreadable_digest_exits_2_not_1(self) -> None:
        self._fake_apksigner("Verifies\nNumber of signers: 1\n")
        result = self.run_script(VERIFY, "--expected", DIGEST, self._apk())
        self.assertEqual(result.returncode, 2, result.stderr)

    def test_rejects_a_malformed_expected_digest(self) -> None:
        self._fake_apksigner(BUILD_TOOLS_37_OUTPUT)
        result = self.run_script(VERIFY, "--expected", "", self._apk())
        self.assertEqual(result.returncode, 2)


class SignReleaseTest(_FakeToolsCase):
    def setUp(self) -> None:
        super().setUp()
        self.log = self.tmp / "calls.log"
        # zipalign: copy input to output. apksigner: log argv, write --out.
        _write_exe(
            self.bin / "zipalign",
            f'echo "zipalign $*" >> "{self.log}"\n'
            'cp "${@: -2:1}" "${@: -1}"\n',
        )
        _write_exe(
            self.bin / "apksigner",
            f'echo "apksigner $*" >> "{self.log}"\n'
            'echo "ks_pass_env=$KS_PASS" >> "' + str(self.log) + '"\n'
            'while [ "$#" -gt 1 ]; do\n'
            '  if [ "$1" = "--out" ]; then out="$2"; fi\n'
            "  shift\n"
            "done\n"
            'echo signed > "$out"\n',
        )
        self.keystore = self.tmp / "ks.p12"
        self.keystore.write_bytes(b"ks")
        self.env.update(KS_PASS="store-secret-123", KEY_PASS="key-secret-456", KEY_ALIAS="saraswati")

    def test_signs_two_apks_with_passwords_only_via_env(self) -> None:
        app_in, upd_in = self.tmp / "app-unsigned.apk", self.tmp / "updater-unsigned.apk"
        app_in.write_bytes(b"app")
        upd_in.write_bytes(b"updater")
        app_out, upd_out = self.tmp / "app-signed.apk", self.tmp / "updater-signed.apk"
        result = self.run_script(
            SIGN, "--keystore", self.keystore,
            "--in", app_in, "--out", app_out,
            "--in", upd_in, "--out", upd_out,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(app_out.is_file())
        self.assertTrue(upd_out.is_file())
        calls = self.log.read_text(encoding="utf-8")
        self.assertEqual(calls.count("zipalign -p 4 "), 2)
        self.assertEqual(calls.count("apksigner sign "), 2)
        self.assertIn("--ks-pass env:KS_PASS", calls)
        self.assertIn("--key-pass env:KEY_PASS", calls)
        self.assertIn("--ks-key-alias saraswati", calls)
        argv_lines = [line for line in calls.splitlines() if not line.startswith("ks_pass_env=")]
        for secret in ("store-secret-123", "key-secret-456"):
            self.assertNotIn(secret, "\n".join(argv_lines), "a password leaked onto a command line")
        self.assertIn("ks_pass_env=store-secret-123", calls)

    def _write_unsigned(self) -> Path:
        app_in = self.tmp / "app-unsigned.apk"
        app_in.write_bytes(b"app")
        return app_in

    def test_apksigner_failure_exits_1_with_a_pkcs12_hint(self) -> None:
        # apksigner itself exits 2 (e.g. UnrecoverableKeyException from a
        # wrong key password); the script must report 1, keeping 2 for usage.
        _write_exe(self.bin / "apksigner", "echo boom >&2; exit 2\n")
        result = self.run_script(
            SIGN, "--keystore", self.keystore,
            "--in", self._write_unsigned(), "--out", self.tmp / "o.apk",
        )
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("apksigner failed", result.stderr)
        self.assertIn("KS_PASS and KEY_PASS differ", result.stderr)
        self.assertNotIn("store-secret-123", result.stderr + result.stdout)
        self.assertNotIn("key-secret-456", result.stderr + result.stdout)

    def test_zipalign_failure_exits_1(self) -> None:
        _write_exe(self.bin / "zipalign", "exit 1\n")
        result = self.run_script(
            SIGN, "--keystore", self.keystore,
            "--in", self._write_unsigned(), "--out", self.tmp / "o.apk",
        )
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("zipalign failed", result.stderr)

    def test_missing_password_env_is_a_usage_error(self) -> None:
        del self.env["KEY_PASS"]
        app_in = self.tmp / "app-unsigned.apk"
        app_in.write_bytes(b"app")
        result = self.run_script(
            SIGN, "--keystore", self.keystore, "--in", app_in, "--out", self.tmp / "o.apk"
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("KEY_PASS", result.stderr)

    def test_unpaired_in_out_is_a_usage_error(self) -> None:
        app_in = self.tmp / "app-unsigned.apk"
        app_in.write_bytes(b"app")
        result = self.run_script(SIGN, "--keystore", self.keystore, "--in", app_in)
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()

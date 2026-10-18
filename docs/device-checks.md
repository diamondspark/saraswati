# Device checks (Task 10)

Release-like build mechanics, the smoke-test script, and the white-box
checks the brief calls for. **The Pixel 8 Pro was away from the computer
for the whole of this task** (the user was out with their phone), so every
step that needs a real device or a signed-in account is deferred —
precisely listed in `docs/pending-human-steps.md`, never attempted here.
Nothing in this task asked for or handled credentials.

## What this task built (no device needed)

- `scripts/smoke_helpers.py` — `parse_playing_state(dumpsys_text, package)
  -> bool`, unit-tested in `scripts/test_smoke_helpers.py` (the brief's
  three named cases plus two extra edge cases: a package absent from the
  dump, and a multi-session dump where only one of two packages is
  playing). RED (`ModuleNotFoundError: No module named 'smoke_helpers'`)
  then GREEN (5/5, full `scripts/` suite 17/17) — see the Task 10 report
  for the exact transcript.
- `scripts/perf-baseline.sh` (Task 13) now imports this helper for its
  tap-to-audio check instead of keeping its own stand-in; see
  `docs/perf-baseline.md`'s "Known gap" section (now marked resolved).
- `scripts/smoke.sh <apk> <package> [video_id]` — the device smoke test
  below.

## Build matrix

Both variants were built from this task's commit with
`source scripts/dev-env.sh && sar_gradle ...`, one build at a time, no
emulator running concurrently.

### Debug (`dev.diamondspark.saraswati.debug`, debuggable — for white-box checks)

```bash
source scripts/dev-env.sh
sar_ensure_debug_keystore
sar_gradle :app:assembleGmsDebug
```

Output: `app/build/outputs/apk/gms/debug/app-gms-debug.apk`, signed with
the debug key (`CN=Android Debug`) — see Result below for the exact size
and build time from this run.

### Release-like (`dev.diamondspark.saraswati`, `isDebuggable=false`, minified)

`app/build.gradle.kts`'s `release` build type has no `signingConfig`
assigned (the real `release` `signingConfig` needs `keystore/release.keystore`
plus `STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`, none of which exist on
this machine — that's Phase 2's CI-signed release), so `assembleGmsRelease`
produces an **unsigned** APK that is then signed locally with the debug
key, purely to prove the build/signing mechanics work end to end:

```bash
sar_gradle :app:assembleGmsRelease
apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out /tmp/saraswati-local.apk \
  app/build/outputs/apk/gms/release/app-gms-release-unsigned.apk
apksigner verify /tmp/saraswati-local.apk
```

**This local signature is temporary and not the shipping signature.**
Installing the real, CI-signed Phase 2 release later needs an uninstall of
this locally-signed one first (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`
otherwise, since the signing keys differ) — and an uninstall loses the
signed-in session. Don't install this local build on the Pixel as a
long-term "release" copy; it exists only to smoke-test, same as the debug
variant.

### Result

| Item | Value |
| --- | --- |
| Tree | this task's commit, branch `phase1-harden` |
| `:app:assembleGmsDebug` | `BUILD SUCCESSFUL in 40s`. `app/build/outputs/apk/gms/debug/app-gms-debug.apk`, 50,893,302 bytes (48.5 MiB), signed with the debug key. |
| `:app:assembleGmsRelease` | `BUILD SUCCESSFUL in 2m 58s`. `app/build/outputs/apk/gms/release/app-gms-release-unsigned.apk`, 26,058,017 bytes (24.9 MiB, minified/shrunk, unsigned). |
| Local re-sign | `apksigner sign` with `~/.android/debug.keystore` → 26,184,580 bytes. `apksigner verify`: Verified, v2 + v3 signature schemes, 1 signer. |
| Manifest/permission gate | `python3 -I security/apk_gate.py check --apk <signed apk> --allowlist security/manifest-allowlist.json` → exit 0 (clean) against the release-like build, confirming it still matches Task 4's allowlist — the same allowlist `scripts/smoke.sh` checks at runtime. |
| Installing on a real device | **Deferred** — the Pixel was unavailable this run; see `docs/pending-human-steps.md` |

## `scripts/smoke.sh`

```
scripts/smoke.sh [-s SERIAL] [--skip-playback] <apk> <package> [video_id]
```

Runs 8 checks, in order, exiting non-zero on the first failure: install
(`adb install -r -d`), grant `POST_NOTIFICATIONS`, launch via `monkey`,
process still alive 8s later, the crash log buffer is empty, the package's
requested permissions (from `dumpsys package`) exactly equal
`security/manifest-allowlist.json`'s allowlist (the one package-scoped
entry, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, is rebuilt against
whichever `<package>` you pass so the same allowlist file works for both
the debug and release-like applicationId), no `ALLOW_BACKUP` flag, and —
unless `--skip-playback` is passed — a signed-in deep link to
`https://music.youtube.com/watch?v=<video_id>` polled against
`dumpsys media_session` for PLAYING via `smoke_helpers.parse_playing_state`,
within 20s.

`--skip-playback` exists because that last check needs a real, signed-in
YouTube account; no agent ever signs in or handles credentials, so a
device/build that cannot sign in (the emulator, or a fresh install before
the human checkpoint) must pass it explicitly. The brief's own human
checkpoint (`docs/pending-human-steps.md`) runs this script **without**
`--skip-playback`, on the Pixel, once the user has signed in through the
app's own UI.

### What was sanity-tested without the Pixel

- Argument parsing and all usage/validation paths (`--help`, missing args,
  a non-existent APK path, an empty package) — exercised directly, no
  device touched.
- The non-device parsing logic — `extract_requested_permissions`'s `awk`
  block and `expected_permissions`'s Python substitution — verified
  against a captured-shape `dumpsys package` fixture and both package
  spellings (`dev.diamondspark.saraswati` and the `.debug` suffix); and the
  `ALLOW_BACKUP` grep verified against both a flags line with and without
  that token. See the Task 10 report for the exact commands and output.
- A real dry run of checks 1-7 against the headless emulator
  (`-s emulator-5554`, the debug APK of **Saraswati itself**, never
  Metrolist or the Pixel), Gradle stopped first: `scripts/smoke.sh -s
  emulator-5554 --skip-playback app/build/outputs/apk/gms/debug/app-gms-debug.apk
  dev.diamondspark.saraswati.debug` → all 7 checks `[PASS]`, check 8
  `[SKIP]` as requested. Checks 2 (grant) and 6 (permissions) exercised
  their real, non-stand-in paths (SDK 36 on this AVD has runtime
  `POST_NOTIFICATIONS`, so check 2 took the real grant branch, not the
  "Unknown permission" fallback). A second run against a deliberately
  wrong package name failed fast at check 2 with `Failure [package not
  found]` and exit code 1, confirming the fail-fast contract. Check 8 (the
  signed-in deep link) was never attempted there, since no account can
  sign in on the emulator. Cleaned up afterward: `adb -s emulator-5554
  uninstall dev.diamondspark.saraswati.debug`, then `emu.sh stop`;
  `pgrep -af '[q]emu-system'` confirmed no stray process. The Pixel was
  never touched.

## White-box checks (Step 5) — all deferred

Every item in the brief's Step 5 needs the Pixel, a signed-in account, or
both. Each is listed with its exact command in
`docs/pending-human-steps.md`'s "Task 10" section:

- The Task 7 cookie-file check (`run-as ... | grep -c SAPISID` prints `0`).
- The `ACTION_RECOGNITION` explicit-intent check (opens normally, no crash).
- The `docs/strip-checklist.md` manual click-through (all 10 items).
- A playlist export + crash-share round trip, to confirm the narrowed
  FileProvider (`app/src/main/res/xml/provider_paths.xml`, Task 6) still
  works for both call sites (`PlaylistExporter.kt`, `CrashActivity.kt`).

## Deferred (needs the Pixel and/or a signed-in account)

See `docs/pending-human-steps.md`'s "Task 10" section for the full,
step-by-step list with exact commands:

1. Install both variants on the Pixel and run `scripts/smoke.sh` against
   each **without** `--skip-playback`, after signing in through the app's
   own UI (brief Step 4, human checkpoint).
2. All four white-box checks above (brief Step 5).
3. Uninstalling the debug variant from the Pixel afterward (brief Step 6)
   — it is debuggable and should not stay installed.

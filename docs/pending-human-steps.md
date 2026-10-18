# Pending human steps

Device-dependent checks that no agent should attempt unattended: each one either
needs a real, signed-in YouTube account (never hand credentials to an agent) or
needs the Pixel physically present. Do these once the Pixel 8 Pro is back and
you're ready to sign in with the family's real account.

General rules for every step below:
- Always pass `-s <PIXEL_SERIAL>` on every `adb` call (or `export ANDROID_SERIAL=<PIXEL_SERIAL>`
  for the shell session) so nothing accidentally targets the emulator or another device.
- Never uninstall or modify `com.metrolist.music` (the real Metrolist app) on the Pixel —
  only `dev.diamondspark.saraswati.debug`.
- If any step below finds a legitimately-needed host that the policy blocks, add it with a
  one-line reason, in both `security/egress-allowlist.json` **and** the matching
  `<domain-config>` in `app/src/main/res/xml/network_security_config.xml`, then re-run
  `python3 -I -m unittest discover -s security -p 'test_*.py' -v` (it asserts the two files
  stay in sync) before committing.

## Task 10 — human checkpoint, white-box checks (brief Steps 4-6)

Task 10 built and proved, without the Pixel: `scripts/smoke_helpers.py`'s
`parse_playing_state` (unit-tested, RED then GREEN), `scripts/smoke.sh`
(argument parsing, non-device parsing logic, and a real dry run of checks
1-7 against the headless emulator with a debug build of Saraswati itself —
never Metrolist, never the Pixel), and both the debug and a locally
re-signed release-like APK (`docs/device-checks.md` has the exact build
commands and sizes). Everything below needs the Pixel, a signed-in
account, or both — do these once the Pixel 8 Pro is back.

1. **Install both variants on the Pixel:**
   ```bash
   source scripts/dev-env.sh
   export ANDROID_SERIAL=<PIXEL_SERIAL>
   sar_gradle :app:assembleGmsDebug :app:assembleGmsRelease
   apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
     --ks-key-alias androiddebugkey --key-pass pass:android \
     --out /tmp/saraswati-local.apk \
     app/build/outputs/apk/gms/release/app-gms-release-unsigned.apk
   adb install -r -d app/build/outputs/apk/gms/debug/app-gms-debug.apk
   adb install -r -d /tmp/saraswati-local.apk
   ```
2. **Sign in with the real YouTube account** on each variant you intend to
   smoke-test, through the app's own Login screen (manual — your hands,
   your password; never paste credentials into any tool).
3. **Run the smoke script against both, without `--skip-playback`:**
   ```bash
   scripts/smoke.sh app/build/outputs/apk/gms/debug/app-gms-debug.apk dev.diamondspark.saraswati.debug
   scripts/smoke.sh /tmp/saraswati-local.apk dev.diamondspark.saraswati
   ```
   Expected: all 8 checks `[PASS]` for both, including playback reaching
   PLAYING. If check 8 times out, confirm you actually signed in on that
   variant first (the error message says so).
4. **White-box checks on the debug variant** (`dev.diamondspark.saraswati.debug`):
   - Cookie file is sealed, not plaintext (Task 7):
     ```bash
     adb exec-out run-as dev.diamondspark.saraswati.debug \
       cat files/datastore/settings.preferences_pb | grep -c SAPISID
     ```
     Expected: `0`.
   - The removed recognizer intent-filter doesn't leave a live entry point
     via an explicit component start (Task 5):
     ```bash
     adb shell am start -a com.metrolist.music.action.RECOGNITION \
       -n dev.diamondspark.saraswati.debug/com.metrolist.music.MainActivity
     ```
     Expected: the app opens normally (last screen or Home on a cold
     start) — no recognition screen, no crash, nothing in
     `adb logcat -d | grep -i recogni`.
   - Walk `docs/strip-checklist.md`'s "Manual click-through (Task 10)"
     list (all 10 items: Settings root, Player & Audio, Content, Lyrics
     provider selection, Home, Search, search long-press, Listen Together,
     the recognizer intent above, general smoke). Record any deviation —
     a stray row, a blank section, a crash — as a new finding, not
     something to paper over.
   - Playlist export + crash share round trip, to confirm the narrowed
     FileProvider (`app/src/main/res/xml/provider_paths.xml`, Task 6)
     still works for both call sites: export any playlist as CSV or M3U
     and share it (should open a normal share sheet, no
     `FileUriExposedException`/`SecurityException` in `adb logcat -d`);
     separately, trigger a crash and use the crash screen's Share button
     (`CrashActivity`) and confirm the same — no exposure exception, the
     shared file is under `cache/shared/`, and (per Task 10's audit note,
     A11-3) it's swept away again the next time `CrashActivity` opens.
5. **Uninstall the debug variant** (`dev.diamondspark.saraswati.debug`)
   from the Pixel when you're done — it is debuggable and should not stay
   installed:
   ```bash
   adb uninstall dev.diamondspark.saraswati.debug
   ```
   The locally-signed release-like variant (`dev.diamondspark.saraswati`)
   can stay installed for now if you want to keep using it; just remember
   that moving to the real CI-signed Phase 2 release later needs an
   uninstall first (the signing keys differ, so Android refuses the
   update otherwise), which loses that sign-in.

## Task 12 — network egress allowlist, dynamic evidence (brief Steps 3-5)

Task 12 built and proved the deny-by-default mechanism itself on the emulator
(`EgressPolicyInstrumentedTest`: an allowed host completes the TLS handshake, two unlisted
hosts — including the real Listen Together server — fail it). What that proof could **not**
cover is whether the final allowlist (`youtube.com`, `googlevideo.com`, `ytimg.com`,
`ggpht.com`, `googleusercontent.com`, `gstatic.com`, `google.com`, plus exact hosts
`lrclib.net` and `lyrics-api.boidu.dev`) is actually *sufficient* for every kept feature when
exercised for real, signed in, on a phone.

1. Install the current debug build on the Pixel:
   `source scripts/dev-env.sh && export ANDROID_SERIAL=<PIXEL_SERIAL> && sar_gradle :app:installGmsDebug`.
2. Clear logs: `adb -s <PIXEL_SERIAL> logcat -c`.
3. Sign in with the real YouTube account through the app's own Login flow (manual — never
   automate this, never paste credentials into any tool).
4. Exercise, in one session: Home screen, search, play three songs (one of them in Hindi),
   open lyrics for a song that has both an LrcLib and a BetterLyrics-sourced result, browse
   artist/album images in Library, download a song, start a Cast session (if a Cast target is
   on the network), open the Listen Together screen (just opening the screen and tapping
   Connect is enough — it is expected to fail to connect under this policy; that failure *is*
   the feature working as designed until Phase 4).
5. Pull the log and check for TLS failures:
   `adb -s <PIXEL_SERIAL> logcat -d | grep -E "SSLHandshakeException|CertPathValidatorException|Trust anchor"`.
   List every distinct host that appears.
6. Same session, same log pull, also check the **separate**, app-level WebView navigation
   policy from Task 15 (`LoginUrlPolicy` — a different mechanism than the network config; it
   decides what the sign-in WebView itself is allowed to *navigate to*, before the request ever
   reaches the network layer): `adb -s <PIXEL_SERIAL> logcat -d | grep "LoginUrlPolicy blocked"`.
   Task 15's own report deferred exactly this check ("if a legitimate host the real flow needs
   — e.g. a 2FA/passkey interstitial, or a country-domain shape not covered by the current
   regex — shows up blocked, add it to `LoginUrlPolicy` with a new test case before shipping").
   It's grouped here because it's the same kind of device-only check, just a different file
   (`app/src/main/kotlin/com/metrolist/music/saraswati/security/LoginUrlPolicy.kt`, test at
   `app/src/test/kotlin/com/metrolist/music/saraswati/security/LoginUrlPolicyTest.kt`).
7. For every host step 5 or step 6 flags: decide whether it's needed for a feature you want to
   keep.
   - Known candidates worth watching for, from the audit's host-inventory (unverified on a real
     device): `fonts.googleapis.com` and `jnn-pa.googleapis.com` (both `googleapis.com`, not
     `google.com` — deliberately left out; allowing either would also allow
     `generativelanguage.googleapis.com`/Gemini, so prefer a narrower fix such as an exact-host
     entry for just the one needed subdomain over opening all of `googleapis.com`), a ccTLD
     Google account hop (e.g. `accounts.google.de`, not a subdomain of `google.com`), and
     `googleads.g.doubleclick.net`/`static.doubleclick.net` (ads — expected to stay blocked).
   - Needed → add to `security/egress-allowlist.json` (prefer an exact host over a wider
     subdomain wildcard unless you've seen more than one sibling subdomain in use) and the
     matching `<domain-config>` in `network_security_config.xml`, with a one-line reason in
     `docs/security-audit.md`'s A12-k row. Add/extend a case in `security/test_egress_policy.py`
     only if the existing four tests don't already cover the new entry (they should, since they
     assert the JSON and XML stay in sync generically).
   - Not needed → leave it blocked; note the feature degrades (e.g. a blank avatar, a missing
     font) rather than crashing.
8. Check whether the WebView actually **honors** the network policy end to end: if sign-in
   completes smoothly with zero `SSLHandshakeException`/`CertPathValidatorException` entries
   for any Google host, that's the expected, good outcome. If sign-in stalls or the WebView
   shows its own cert-error page, that's the policy correctly blocking a sub-resource the real
   flow needs — go to step 7. If the WebView completes sign-in despite a blocked sub-resource
   showing up in logcat, the WebView is tolerating the failure (e.g. a font fallback); note that
   in `docs/security-audit.md` rather than treating it as broken.
9. Run the full feature-verification pass from the brief (`scripts/smoke.sh`, plus a
   screenshot check if you have `rea-agents` set up): confirm Home art is visible, lyrics show
   for a known song, and sign-in persists after a force-stop/relaunch.
10. Re-run both suites as final evidence: the JVM suite
    (`source scripts/dev-env.sh && sar_gradle :app:cleanTestGmsDebugUnitTest :app:testGmsDebugUnitTest`)
    and the Python policy suite (`python3 -I -m unittest discover -s security -p 'test_*.py' -v`).
    If step 7 added anything, commit it as its own `fix(security): ...` commit referencing this
    checklist, not folded silently into a later task's commit.

## Task 13 — performance and Android Auto baselines (brief Steps 3-4)

Task 13 built and sanity-checked the measurement tooling itself
(`scripts/perf_stats.py`, `scripts/test_perf_stats.py`, `scripts/perf-baseline.sh`;
dry-run proof against the headless emulator's own `com.android.settings`, not
Metrolist or Saraswati, in `docs/perf-baseline.md`). It could not run a real
measurement: Metrolist isn't installed on the emulator, no account can sign
in there, and the Android Auto Desktop Head Unit (DHU) needs the Pixel
physically at the computer over USB. Both deferred docs
(`docs/perf-baseline.md`, `docs/android-auto-baseline.md`) are templates with
budgets and exact commands, numbers left as TODO.

1. **Performance baseline** (`docs/perf-baseline.md`). Install Metrolist
   13.6.3 and the current Saraswati build side by side on the Pixel, both
   signed in, screen on, battery > 50%, same Wi-Fi:
   ```bash
   source scripts/dev-env.sh
   export ANDROID_SERIAL=<PIXEL_SERIAL>
   scripts/perf-baseline.sh com.metrolist.music 10
   scripts/perf-baseline.sh dev.diamondspark.saraswati 10
   ```
   Fill in `docs/perf-baseline.md`'s Results table from the two reports and
   state plainly which budgets (cold start ≥20% faster than Metrolist and
   ≤1.0 s; tap-to-audio p50 ≤1.0 s uncached; jank <1%) are not yet met —
   Phase 2's Macrobenchmark and Baseline Profile work is what closes them,
   not this task.
2. **Android Auto baseline** (`docs/android-auto-baseline.md`), with the user
   at the computer:
   - Enable developer mode and "Unknown sources" in the Android Auto app on
     the Pixel.
   - `adb forward tcp:5277 tcp:5277`, then start the head unit server from
     the Android Auto app's developer settings.
   - Launch the DHU: `"${ANDROID_HOME:-$HOME/Android/Sdk}/extras/google/auto/desktop-head-unit/desktop-head-unit"`.
   - Run the checklist in `docs/android-auto-baseline.md` for Saraswati,
     then again for Metrolist: app listed, root browse tree loads, play a
     song, next/previous, voice search through the DHU, resume after
     reconnect, and `adb logcat -d | grep "ControllerPolicy denied"` shows
     **no match**. Record results and any timing in that file's table.
   - If a denial or a missing host shows up, treat it as a real finding
     (Task 8's controller policy or Task 12's egress allowlist), not
     something to paper over to make the checklist pass.
3. **Known cross-task gap: resolved.** Task 10 has since landed
   `scripts/smoke_helpers.py`'s unit-tested `parse_playing_state`, and
   `scripts/perf-baseline.sh`'s `is_playing` now calls into it instead of
   its own stand-in. Nothing left to reconcile here.

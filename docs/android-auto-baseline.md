# Android Auto baseline (Task 13)

**Status: TODO — not yet run.** This page is a template. It records the
budgets, the checklist, and the exact commands to run once the Pixel 8 Pro
is physically at the computer with the user present (this step needs a
Desktop Head Unit session over USB, which the Pixel being merely "online"
cannot provide). The results below are placeholders — do not fill them in
from guesswork.

## Why this is deferred, not attempted on the emulator

The brief is explicit that this step runs "user at the computer." Beyond
that, the headless test emulator cannot stand in for it at all:

- The Desktop Head Unit (DHU) talks to Android Auto over a physical
  `adb forward tcp:5277 tcp:5277` to a device running the real Android Auto
  app (`com.google.android.projection.gearhead`) with developer mode and
  "Unknown sources" enabled — this is a real-hardware USB/adb protocol path,
  not something the emulator's virtual hardware exposes.
- Both Saraswati and Metrolist need to be installed and **signed in** for
  "play a song" / "voice search" / "resume after reconnect" to mean
  anything; no account can sign in on the emulator.
- Android Auto's own controller-policy check (Task 8) validates the real
  Android Auto app's signer digest — harvested from the Pixel before it
  left (see `docs/pending-human-steps.md` / the SDD progress ledger, Ruling
  R7) — so a meaningful "no `ControllerPolicy denied`" result can only come
  from the real app on the real phone.

This checklist is also appended to `docs/pending-human-steps.md` so it is
tracked in one place with the other deferred, device-only steps.

## Budgets (design spec, "Speed and stability budgets")

| Metric | Budget | How it's measured |
| --- | --- | --- |
| Car: connect to browse tree shown | ≤ 1.0 s (cached) | Desktop Head Unit plus logs |
| Car: tap to audio with the phone locked | p50 ≤ 1.5 s | Desktop Head Unit plus Media3 `AnalyticsListener` |

The DHU session below also has to show **zero** Android Auto denials
(`adb logcat -d | grep "ControllerPolicy denied"`) — that's a correctness
gate from Task 8, not a performance number, but it's checked in the same
session because both need the DHU connected.

## Setup, once the Pixel is at the computer

1. **Enable developer mode and "Unknown sources" in Android Auto on the
   Pixel** (Settings → About phone → tap Build number repeatedly if needed,
   then open the Android Auto app → tap its version/menu repeatedly to
   reveal developer settings → enable "Unknown sources").
2. **Connect the Pixel over USB** and forward the DHU port:
   ```bash
   source scripts/dev-env.sh
   export ANDROID_SERIAL=<PIXEL_SERIAL>   # never commit the real serial
   adb forward tcp:5277 tcp:5277
   ```
3. **Start the head unit server on the phone** — in the Android Auto app's
   developer settings, "Start head unit server".
4. **Launch the Desktop Head Unit on the computer:**
   ```bash
   "${ANDROID_HOME:-$HOME/Android/Sdk}/extras/google/auto/desktop-head-unit/desktop-head-unit"
   ```
   (installed by Phase 0's `sdkmanager "extras;google;auto"`; the brief's
   path `~/Android/Sdk/extras/google/auto/desktop-head-unit` is the same
   file, `$ANDROID_HOME`-relative per constraints.md R2).
5. Clear logs before each app's run so denials are attributable:
   `adb logcat -c`.

## Checklist (run once for Saraswati, once for Metrolist, for comparison)

Record pass/fail and any timing for both. For `dev.diamondspark.saraswati`,
the DHU should also recognize the debug or release-like build installed by
Task 10 — note which variant was used.

| # | Check | Saraswati result | Metrolist result |
| --- | --- | --- | --- |
| 1 | App listed in the DHU launcher | TODO | TODO |
| 2 | Root browse tree loads | TODO | TODO |
| 3 | Play a song | TODO | TODO |
| 4 | Next / previous track | TODO | TODO |
| 5 | Voice search through the DHU | TODO | TODO |
| 6 | Resume after reconnect (disconnect/reconnect the DHU mid-session) | TODO | TODO |
| 7 | `adb logcat -d \| grep "ControllerPolicy denied"` — expect **no match** | TODO | N/A (Metrolist predates the controller policy) |

Also record, for row 2 and row 3, the elapsed time against the two budgets
above (connect-to-browse-tree ≤ 1.0 s cached; tap-to-audio with phone locked
p50 ≤ 1.5 s) if the DHU's own logs make that practical to time; otherwise
note it as "not timed, pass/fail only" rather than guessing a number.

## Results

TODO: measure once the Pixel is available and the user is at the computer.

State plainly which rows fail, and whether a failure is a missing
Android Auto host in the Task 12 egress allowlist, a `ControllerPolicy`
digest mismatch (Task 8), or something else — do not guess at a root cause
without the logcat evidence in hand.

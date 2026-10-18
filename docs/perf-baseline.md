# Performance baseline (Task 13)

**Status: TODO — not yet measured.** This page is a template. It records what
the measurement tooling does, the exact budgets it is judged against, and
the exact commands to run once the Pixel 8 Pro is back and both apps are
signed in. The numbers below are placeholders, not estimates — Task 13 built
the measurement scripts but could not run a real measurement: the Pixel was
away from the computer for the whole of Phase 1, and the two numbers that
matter (cold start, tap-to-audio) are only meaningful on the real phone with
both apps signed in to the family's YouTube account — see
"Why this can't be measured on the emulator" below.

## Budgets (from the design spec, Pixel 8 Pro)

| Metric | Budget | How this page measures it |
| --- | --- | --- |
| Cold start to cached Home | ≥ 20% faster than Metrolist 13.6.3, and ≤ 1.0 s | `scripts/perf-baseline.sh`: median/p90 of `adb shell am start -W`'s `TotalTime`, 9 kept runs (10 attempts, first discarded) |
| Tap to first audio, not cached | p50 ≤ 1.0 s on Wi-Fi | `scripts/perf-baseline.sh`: median/p90 of elapsed time from a `music.youtube.com/watch?v=...` deep link to the package reaching `PLAYING` in `dumpsys media_session` |
| Tap to audio, cached or downloaded | ≤ 300 ms | Not measured by this script (it always force-stops first, so every run is "not cached"); a future addition could repeat the same song a second time without force-stopping |
| Scroll jank on Home and Library | < 1% janky frames | `scripts/perf-baseline.sh`: median/p90 of `dumpsys gfxinfo <package>`'s `Janky frames: N (P%)` line, after 10 scripted swipes on Home |
| Crash-free sessions, playback failures | ≥ 99.5%, < 0.5% | Sentry (Phase 2) — out of scope here |

The full spec table (`docs/superpowers/specs/2026-10-17-saraswati-design.md`,
"Speed and stability budgets") also lists two Android Auto budgets; those are
in `docs/android-auto-baseline.md`, not here.

**Phase 2's Macrobenchmark and Baseline Profile work is what's expected to
close the cold-start and jank budgets, not this task.** This page's job is to
measure where things stand, not to make them pass.

## Measurement tooling (built now, usable without the Pixel)

- `scripts/perf_stats.py` — four pure functions plus a thin CLI:
  `parse_am_start_total_time`, `parse_gfxinfo_janky_percent`, `median`,
  `p90`. Unit-tested in `scripts/test_perf_stats.py` (9 tests, including the
  brief's exact captured-sample cases: `TotalTime: 545` → 545, a gfxinfo
  block with `Janky frames: 12 (4.80%)` → 4.8, `median([3,1,2]) == 2`, `p90`
  of 1..10 == 9).
- `scripts/perf-baseline.sh <package> <runs>` — drives one package through
  10 attempts per metric (configurable via `<runs>`), discards the first as
  a warm-up, and prints median/p90 for cold start, tap-to-audio, and jank.
  Run it once per package and compare the two printed reports by hand.

### Known gap: resolved

Task 13 originally landed before Task 10 (execution-order gap), so
`perf-baseline.sh` carried its own small, undocumented-format `is_playing`
stand-in rather than importing Task 10's helper. Task 10 has since landed:
`scripts/smoke_helpers.py` now provides the unit-tested
`parse_playing_state(dumpsys_text, package) -> bool`
(`scripts/test_smoke_helpers.py`), and `perf-baseline.sh`'s `is_playing`
calls into it via its CLI (`python3 -I scripts/smoke_helpers.py is-playing
<package>`) instead of re-parsing `dumpsys media_session` itself. The two
scripts now share one tested implementation.

### Why this can't be measured on the emulator

- Metrolist (`com.metrolist.music` 13.6.3) is not installed on the headless
  test emulator, and installing a release APK there would not be a
  meaningful comparison anyway — the spec's budgets are specifically
  "Pixel 8 Pro" numbers, and the emulator's `swiftshader_indirect` software
  GPU and x86_64 translation layer for an arm64 APK make its timings
  incomparable to real hardware.
- Both the cold-start and tap-to-audio metrics need a real YouTube account
  signed in (cold start to "cached Home" means a Home screen full of the
  signed-in account's data; tap-to-audio needs a playable track). No account
  can sign in on the emulator, and credentials are never handled by an
  agent.

### What was actually verified (no Saraswati/Metrolist numbers involved)

To prove the parsing pipeline itself is correct against real device output
(not just the brief's synthetic fixtures), `perf-baseline.sh` was dry-run
against the headless test emulator (`-s emulator-5554`, never the Pixel)
using `com.android.settings` — a pre-installed system app, not Metrolist or
Saraswati — as a stand-in target:

```
$ scripts/perf-baseline.sh -s emulator-5554 com.android.settings 3
...
=== Cold start ===
n=2 (of 3 attempts: 1 discarded as warm-up, 0 failed)
median: 978ms   p90: 1369ms
=== Tap-to-audio (deep link to PLAYING) ===
no usable samples out of 3 attempt(s) (3 failed; ...) — see warnings above
=== Scroll jank on Home ===
n=2 (of 3 attempts: 1 discarded as warm-up, 0 failed)
median: 2.15% janky frames   p90: 2.98% janky frames
```

This confirms: `am start -W`'s real `TotalTime` line is parsed into real
millisecond values end to end; `dumpsys gfxinfo`'s real `Janky frames: N
(P%)` line is parsed correctly even though modern Android also prints a
second, unrelated `Janky frames (legacy): N (P%)` line right next to it (the
parser's regex requires `frames:` immediately after `Janky`, so it does not
match the legacy line); and the scripted-swipe and warm-up/discard-first
logic runs correctly. The tap-to-audio check correctly fails fast and warns
(Settings has no activity that handles a `music.youtube.com` deep link) —
this exercises the script's failure-handling path, not a real measurement.
**None of these three numbers say anything about Saraswati or Metrolist's
performance; they only prove the measurement pipeline works.**

## How to measure for real, once the Pixel is back

Preconditions: screen on, battery > 50%, the Pixel on the same Wi-Fi network
for both packages' sessions, and both packages signed in to the family's
YouTube account (Metrolist 13.6.3 and the current Saraswati debug or
release-like build installed side by side — see `docs/device-checks.md` /
Task 10 for how that's done without losing either app's sign-in).

```bash
source scripts/dev-env.sh
export ANDROID_SERIAL=<PIXEL_SERIAL>   # never commit the real serial

scripts/perf-baseline.sh com.metrolist.music 10       > /tmp/metrolist-perf.txt
scripts/perf-baseline.sh dev.diamondspark.saraswati 10 > /tmp/saraswati-perf.txt
```

Then fill in the table below from the two reports and re-check each budget.

## Results

TODO: measure once the Pixel is available.

| Metric | Metrolist 13.6.3 | Saraswati | Budget met? |
| --- | --- | --- | --- |
| Cold start median (ms) | TODO | TODO | TODO |
| Cold start p90 (ms) | TODO | TODO | TODO |
| Tap-to-audio median (ms) | TODO | TODO | TODO |
| Tap-to-audio p90 (ms) | TODO | TODO | TODO |
| Scroll jank median (%) | TODO | TODO | TODO |
| Scroll jank p90 (%) | TODO | TODO | TODO |

State plainly, once filled in, which budgets are **not** yet met — do not
round a miss into a pass. Phase 2's Macrobenchmark and Baseline Profile work
(design spec, Phase 2 step 6) is what is expected to close any cold-start or
jank gap; this page only measures.

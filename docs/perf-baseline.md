# Performance baseline (Task 13, measured in Task 11)

**Status: measured.** Task 13 built the measurement tooling
(`scripts/perf-baseline.sh`, `scripts/perf_stats.py`) but could not run a
real measurement (the Pixel was away from the computer for all of Phase 1).
Task 11 (Phase 2: Macrobenchmark and Baseline Profile module) ran the real
measurements on the Pixel 8 Pro, both with the old `perf-baseline.sh` tool
and with the new, more rigorous `androidx.benchmark` Macrobenchmark suite
(`:baselineprofile`). See "Results" below for the numbers and two
measurement-condition caveats that apply to this pass (battery below the
ideal 50% threshold, and a lock-screen complication partway through — both
explained there) plus a `scripts/perf-baseline.sh` compatibility gap this
pass found on the Pixel's current OS build.

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

### Measurement-condition caveats (read before trusting any number below)

- **Battery was ~44-47%, not the >50% the methodology above asks for.**
  USB charging on this device trickle-charges at 0.5A, which heavy
  benchmark workload (screen on, CPU active) outpaces — level actually
  *dropped* from 45% to 44% over the course of this measurement pass rather
  than climbing. Waiting longer would not have fixed this without a bigger
  charger or an idle-charge period much longer than this task budgeted.
  Measurements below were taken anyway, on direction, with this flagged
  rather than silently ignored.
- **`scripts/perf-baseline.sh`'s cold-start parsing is broken on this
  Pixel's current Android build.** `adb shell am start -W` here prints
  `Status` / `LaunchState` / `Activity` / `WaitTime` / `Complete` — no
  `TotalTime` line at all, which is the only line
  `scripts/perf_stats.py:parse_am_start_total_time` looks for (added in
  Task 13, before this device's current OS build existed). Every
  `perf-baseline.sh` cold-start attempt below failed to parse for this
  reason; it's a pre-existing Task 13 tooling gap this task found, not
  something this task's file scope covers fixing. This is exactly the kind
  of imprecision the brief cites as the reason Macrobenchmark's
  `StartupTimingMetric` (below) is the number to trust for cold start, not
  `perf-baseline.sh`.
- **A lock-screen complication partway through this pass invalidated the
  `perf-baseline.sh` Metrolist run's jank number (and casts doubt on its
  tap-to-audio number).** While waiting for the battery to climb, this
  session sent the device a `KEYCODE_SLEEP` to save power; that triggered
  the Pixel's secure (fingerprint) lock screen, which an agent cannot and
  must not attempt to bypass. `scripts/perf-baseline.sh com.metrolist.music
  10` was then run against the device in this locked state without that
  being noticed until afterward. Its scroll-jank result (0% janky frames,
  both median and p90) is almost certainly an artifact of the swipes never
  reaching the app (nothing was drawn behind the lock screen to be janky),
  not a real measurement, and is **not** reported below. Its tap-to-audio
  result is kept, with a caveat, because media playback can continue behind
  a locked screen (so the deep-link-to-`PLAYING` path doesn't strictly need
  an unlocked screen) — but it was not independently re-verified unlocked.
  The Macrobenchmark suite's results (below) all ran *before* this lock
  engaged, confirmed by a screenshot of a fully loaded, signed-in Home feed
  taken immediately beforehand, so they're unaffected.
- **Net effect:** the budget's *relative* cold-start comparison ("≥20%
  faster than Metrolist 13.6.3") could not be confirmed this pass — the old
  tool can't parse this OS's cold-start output for either package, and nothing
  else in this task's scope stood up an equivalent Metrolist-side
  Macrobenchmark. The *absolute* cold-start budget ("≤1.0s") and Saraswati's
  own jank numbers, both sourced from Macrobenchmark, are solid. See
  "Macrobenchmark results" below for what Task 11 could and couldn't close.

### `scripts/perf-baseline.sh` (Task 13 tooling, Metrolist 13.6.3 only)

| Metric | Metrolist 13.6.3 |
| --- | --- |
| Cold start median / p90 (ms) | not measured — `perf-baseline.sh`'s `TotalTime` parser doesn't match this OS build's `am start -W` output (see caveats) |
| Tap-to-audio median (ms) | 2985 (n=9 of 10, 1 discarded as warm-up) — caveat: device was lock-screen-secured during this run |
| Tap-to-audio p90 (ms) | 3420 — same caveat |
| Scroll jank median / p90 (%) | not reported — almost certainly invalidated by the lock-screen complication (see caveats) |

A Saraswati-side `perf-baseline.sh` run was not attempted: with the
cold-start parser broken for both packages and the jank number already
invalidated for Metrolist, running it a second time for Saraswati would not
have produced a trustworthy "before/after" pair. The Macrobenchmark results
below are Saraswati's authoritative numbers for this task.

### Macrobenchmark results (Task 11, `:baselineprofile`, `dev.diamondspark.saraswati`, `gmsBenchmarkRelease`)

All runs: 10 iterations, first iteration **not** discarded (Macrobenchmark's
own warm-up handling differs from `perf-baseline.sh`'s discard-first
convention — see `StartupBenchmark`/`HomeScrollBenchmark` source). Raw JSON:
`baselineprofile/build/outputs/connected_android_test_additional_output/gmsBenchmarkRelease/connected/Pixel 8 Pro - 17/com.metrolist.music.baselineprofile-benchmarkData.json`
(not committed — build output).

**Cold start — `StartupTimingMetric`, `timeToInitialDisplayMs`:**

| Compilation mode | Median (ms) | Min (ms) | Max (ms) |
| --- | --- | --- | --- |
| `None()` (freshly sideloaded) | 357.4 | 343.3 | 400.9 |
| `Partial()` (baseline profile compiled) | 316.2 | 292.4 | 330.1 |

Budget: **cold start ≤ 1.0s — MET** in both compilation modes, by a wide
margin (worst single sample across both modes: 400.9ms, well under the 1.0s
cap). Budget: **≥20% faster than Metrolist 13.6.3 — NOT CONFIRMED** this
pass (see caveats above); `Partial()` is 11.5% faster than `None()` within
Saraswati itself, which is the baseline profile doing its job, but is not
the Metrolist comparison the budget asks for. An earlier, smaller
preliminary run (before the lock-screen complication, 10 iterations,
`startActivityAndWait()` only, no scroll/player steps sharing the pass) saw
543.3ms (`None()`) / 468.8ms (`Partial()`) — consistent in direction (profile
helps) and still comfortably under 1.0s, just with more absolute overhead
from running right after a cold Gradle/Kotlin-daemon-heavy build session;
the 357.4/316.2ms figures above are from the full, later run and are what's
reported as primary.

**Scroll jank on Home — `FrameTimingMetric`, `frameOverrunMs`** (negative =
frame finished early, i.e. not janky; positive = missed its deadline, i.e.
janky):

| Compilation mode | P50 | P90 | P95 | P99 |
| --- | --- | --- | --- | --- |
| `None()` | -10.13ms | -6.71ms | -6.57ms | -1.81ms |
| `Partial()` | -9.87ms | -6.48ms | -4.50ms | +1.66ms |

Budget: **scroll jank < 1% janky frames — MET.** By definition, P99 is where
at most ~1% of frames could be janky; in `None()` even P99 has no overrun at
all (0% measurably janky across all 10 runs' sampled frames), and in
`Partial()` only P99 just barely crosses into positive overrun (+1.66ms),
meaning at most ~1% of frames were janky, not more. Neither run showed
jank at P95 or below.

**`OpenPlayerBenchmark` (open search, open a Home item, play, open/close the
player) — `FrameTimingMetric`, `CompilationMode.Partial()` only:**

| Metric | P50 | P90 | P95 | P99 |
| --- | --- | --- | --- | --- |
| `frameOverrunMs` | -9.99ms | -6.95ms | -5.78ms | +11.77ms |

This journey is more demanding (search navigation, playback start, player
expand/collapse) and its P99 shows a real, larger overrun than the Home
scroll benchmarks — still consistent with a <1% janky-frame rate (only P99
crosses into jank) but worth watching if Phase 5's speed work looks at
jank beyond Home.

### What's still open after this task

- Confirm the cold-start budget's *relative* claim against Metrolist with a
  valid, unlocked-device run — either fix `scripts/perf_stats.py`'s
  `am start -W` parser for this OS build and re-run `perf-baseline.sh` for
  both packages, or stand up an equivalent Metrolist-targeted Macrobenchmark
  pass.
- Re-run `scripts/perf-baseline.sh dev.diamondspark.saraswati 10` and
  `com.metrolist.music 10` once the device battery is healthier and
  genuinely unlocked throughout, to get a clean jank/tap-to-audio
  "before/after" pair using the Task 13 tool too (not just Macrobenchmark).
- The device's `dev.diamondspark.saraswati` (unsuffixed, `gmsBenchmarkRelease`)
  package is currently freshly (re)installed and signed out — a side effect
  of AGP's connected-test runner uninstalling the app+data after each
  instrumented test run (`-Dandroid-test.uninstall-after-tests=true`, AGP's
  own default, observed directly in this task's Gradle invocations). The
  debug-key signing change in this task's `app/build.gradle.kts` prevents a
  *signature-mismatch* uninstall, but not this separate connected-test
  cleanup step. `dev.diamondspark.saraswati.debug` (the suffixed debug
  build) was never touched by this task and is still the one with real,
  intact account data.
- **Since the Phase 2 final review:** `benchmarkRelease` /
  `nonMinifiedRelease` now carry `applicationIdSuffix = ".benchmark"`, so
  benchmarks and profile generation install and uninstall
  `dev.diamondspark.saraswati.benchmark` — a separate package that
  coexists with the real install (debug- or release-signed) and is the
  only thing AGP's post-test uninstall can wipe. The numbers above were
  measured under the old, unsuffixed package; the package name doesn't
  change the code being measured.

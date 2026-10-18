#!/usr/bin/env bash
# Measures Saraswati's three Task-13 performance metrics for one installed,
# signed-in package: cold start, tap-to-audio (deep link to PLAYING), and
# scroll jank on Home after 10 scripted swipes. Prints median and p90 for
# each. See docs/perf-baseline.md for the budgets these are compared against
# and the exact two-package (Metrolist vs Saraswati) measurement procedure.
#
# Usage:
#   scripts/perf-baseline.sh [-s SERIAL] [-v VIDEO_ID] <package> <runs>
#
# Run once per package (Metrolist, then Saraswati) and compare the two
# reports by hand; this script measures a single package per invocation,
# matching the brief's `perf-baseline.sh <package> <runs>` interface.
#
# <runs> is the TOTAL number of attempts per metric; the first attempt is
# discarded as a warm-up (brief: "10 runs, discard the first"), so stats are
# computed over <runs> - 1 samples. <runs> must be >= 2.
#
# Preconditions (brief Step 2), checked best-effort where a device can tell
# us, otherwise just reminded: screen on, battery > 50%, same Wi-Fi network
# for both packages' measurement sessions, both packages already signed in.
#
# Device selection honors $ANDROID_SERIAL (adb reads it natively) or -s; this
# script never hard-codes a serial (constraints.md R2). It needs `adb` on
# PATH — `source scripts/dev-env.sh` first — and falls back to
# $ANDROID_HOME/platform-tools/adb otherwise.
#
# KNOWN GAP (see docs/perf-baseline.md): the plan's Task 10
# (scripts/smoke.sh / a parse_playing_state helper) has not been implemented
# in this repo yet, so the tap-to-audio check below does its own minimal,
# undocumented-format parsing of `dumpsys media_session` instead of
# importing Task 10's helper. Treat `is_playing` below as a stand-in: once
# Task 10 lands, prefer its tested helper over this one to avoid drift.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PERF_STATS_PY="$SCRIPT_DIR/perf_stats.py"
DEFAULT_VIDEO_ID="dQw4w9WgXcQ" # same default Task 10's brief names for its own deep-link check
TAP_TO_AUDIO_TIMEOUT_S=20       # same timeout Task 10's brief polls PLAYING for

usage() {
  cat <<'EOF'
usage: perf-baseline.sh [-s SERIAL] [-v VIDEO_ID] <package> <runs>

  -s SERIAL   adb device serial (overrides $ANDROID_SERIAL; never hard-code
              the Pixel's serial in a committed invocation)
  -v VIDEO_ID YouTube Music video id for the tap-to-audio deep link
              (default: dQw4w9WgXcQ)
  <package>   application id to measure, e.g. com.metrolist.music or
              dev.diamondspark.saraswati
  <runs>      total attempts per metric; the first is discarded as a
              warm-up, so stats cover <runs> - 1 samples (>= 2 required)

Prints median and p90 for: cold start (ms), tap-to-audio / deep link to
PLAYING (ms), and scroll jank on Home after 10 scripted swipes (% janky
frames). Preconditions: screen on, battery > 50%, same Wi-Fi for every
package you compare, package already signed in. Run once per package and
compare the two reports by hand.
EOF
}

die() {
  echo "perf-baseline.sh: $*" >&2
  exit 1
}

# --- argument parsing (no device access below this point) -----------------

ADB_SERIAL="${ANDROID_SERIAL:-}"
VIDEO_ID="$DEFAULT_VIDEO_ID"

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

while getopts ":s:v:h" opt; do
  case "$opt" in
    s) ADB_SERIAL="$OPTARG" ;;
    v) VIDEO_ID="$OPTARG" ;;
    h) usage; exit 0 ;;
    \?) die "unknown option -$OPTARG (see -h)" ;;
    :) die "option -$OPTARG needs an argument" ;;
  esac
done
shift $((OPTIND - 1))

if [[ $# -ne 2 ]]; then
  usage >&2
  die "expected exactly 2 positional arguments (<package> <runs>), got $#"
fi

PACKAGE="$1"
RUNS="$2"

[[ -n "$PACKAGE" ]] || die "<package> must not be empty"
[[ "$RUNS" =~ ^[0-9]+$ ]] || die "<runs> must be a positive integer, got '$RUNS'"
(( RUNS >= 2 )) || die "<runs> must be >= 2 (one warm-up run is discarded), got $RUNS"

# --- adb wiring --------------------------------------------------------

ADB_BIN="$(command -v adb || true)"
if [[ -z "$ADB_BIN" ]]; then
  ADB_BIN="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
fi
[[ -x "$ADB_BIN" ]] || die "no adb found on PATH or at $ADB_BIN; source scripts/dev-env.sh first"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then
    "$ADB_BIN" -s "$ADB_SERIAL" "$@"
  else
    "$ADB_BIN" "$@"
  fi
}

perf_stats() {
  python3 -I "$PERF_STATS_PY" "$@"
}

# --- preconditions (best-effort, non-fatal) --------------------------------

check_preconditions() {
  local batt_level screen_state
  batt_level="$(adb_cmd shell dumpsys battery 2>/dev/null | awk -F': ' '/level/{print $2}' | tr -d '\r')" || true
  if [[ -n "${batt_level:-}" ]] && [[ "$batt_level" =~ ^[0-9]+$ ]] && (( batt_level <= 50 )); then
    echo "WARNING: battery is ${batt_level}% (<= 50%); the budgets assume >50% to avoid thermal/charging throttling" >&2
  fi
  screen_state="$(adb_cmd shell dumpsys power 2>/dev/null | grep -m1 'mWakefulness=' | sed -E 's/.*mWakefulness=([A-Za-z]+).*/\1/')" || true
  if [[ -n "${screen_state:-}" ]] && [[ "$screen_state" != "Awake" ]]; then
    echo "WARNING: screen does not appear Awake (mWakefulness=$screen_state); wake the device before measuring" >&2
  fi
  echo "NOTE: not checked by this script — confirm by hand: same Wi-Fi network for every package you are comparing, and $PACKAGE is already signed in." >&2
}

# --- cold start (am start -W TotalTime) -------------------------------------

resolve_launch_component() {
  local package="$1" out
  out="$(adb_cmd shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$package" 2>/dev/null | tail -n 1 | tr -d '\r')"
  [[ "$out" == */* ]] && printf '%s' "$out"
}

cold_start_once() {
  local package="$1" component out
  component="$(resolve_launch_component "$package")"
  [[ -n "$component" ]] || { echo "could not resolve a launcher activity for $package" >&2; return 1; }
  adb_cmd shell am force-stop "$package" >/dev/null 2>&1 || true
  sleep 1
  out="$(adb_cmd shell am start -W -n "$component" 2>&1)" || { echo "am start -W failed: $out" >&2; return 1; }
  printf '%s' "$out" | perf_stats am-start-total-time
}

# --- tap-to-audio (deep link to PLAYING) ------------------------------------
# See the KNOWN GAP note at the top of this file: Task 10 hasn't landed, so
# `is_playing` is this script's own stand-in for parse_playing_state, not an
# imported, unit-tested helper. It mirrors the stable AOSP shape
# `PlaybackState.toString()` has printed since Lollipop ("PlaybackState
# {state=<int>, ...}", where 3 is STATE_PLAYING) inside the block `dumpsys
# media_session` prints for a `package=<pkg>` session.

is_playing() {
  local package="$1"
  adb_cmd shell dumpsys media_session 2>/dev/null \
    | grep -A 20 -F "package=$package" \
    | grep -m1 -oE 'state=PlaybackState \{state=[0-9]+' \
    | grep -q 'state=3$'
}

tap_to_audio_once() {
  local package="$1" video_id="$2" start_ms now_ms deadline_ms
  adb_cmd shell am force-stop "$package" >/dev/null 2>&1 || true
  sleep 1
  start_ms="$(date +%s%3N)"
  if ! adb_cmd shell am start -a android.intent.action.VIEW \
      -d "https://music.youtube.com/watch?v=${video_id}" "$package" >/dev/null 2>&1; then
    echo "deep link 'am start' failed for $package" >&2
    return 1
  fi
  deadline_ms=$(( start_ms + TAP_TO_AUDIO_TIMEOUT_S * 1000 ))
  while true; do
    if is_playing "$package"; then
      now_ms="$(date +%s%3N)"
      echo $(( now_ms - start_ms ))
      return 0
    fi
    now_ms="$(date +%s%3N)"
    if (( now_ms >= deadline_ms )); then
      echo "timed out after ${TAP_TO_AUDIO_TIMEOUT_S}s waiting for $package to reach PLAYING" >&2
      return 1
    fi
    sleep 0.2
  done
}

# --- scroll jank on Home after 10 scripted swipes ---------------------------

scripted_swipes() {
  local package="$1" count="${2:-10}" size w h x y1 y2 i
  size="$(adb_cmd shell wm size 2>/dev/null | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p')"
  read -r w h <<< "$size"
  [[ -n "${w:-}" && -n "${h:-}" ]] || { echo "could not read screen size via 'wm size'" >&2; return 1; }
  x=$(( w / 2 ))
  y1=$(( h * 8 / 10 ))
  y2=$(( h * 2 / 10 ))
  for (( i = 0; i < count; i++ )); do
    if (( i % 2 == 0 )); then
      adb_cmd shell input swipe "$x" "$y1" "$x" "$y2" 300 >/dev/null 2>&1 || true
    else
      adb_cmd shell input swipe "$x" "$y2" "$x" "$y1" 300 >/dev/null 2>&1 || true
    fi
    sleep 0.3
  done
}

jank_once() {
  local package="$1" out
  adb_cmd shell am start "$package" >/dev/null 2>&1 || true
  sleep 1
  adb_cmd shell dumpsys gfxinfo "$package" reset >/dev/null 2>&1 || true
  scripted_swipes "$package" 10 || return 1
  # Plain `dumpsys gfxinfo <package>` (no subcommand) prints the
  # pre-computed "Janky frames: N (P%)" summary line that
  # parse_gfxinfo_janky_percent parses (the exact shape the brief's own
  # Step-1 fixture uses). `framestats` instead dumps a raw 900-frame ring
  # buffer with no pre-computed percentage; Phase 2's Macrobenchmark
  # `FrameTimingMetric` is the better fit for that finer-grained data.
  out="$(adb_cmd shell dumpsys gfxinfo "$package" 2>&1)" || { echo "dumpsys gfxinfo failed: $out" >&2; return 1; }
  printf '%s' "$out" | perf_stats gfxinfo-janky-percent
}

# --- driver: run a metric <RUNS> times, discard the first, report stats ----

report_metric() {
  local label="$1" unit="$2" attempted="$3"; shift 3
  local samples=("$@")
  local failed=$(( attempted - ${#samples[@]} ))
  local kept=("${samples[@]:1}")
  echo "=== $label ==="
  if [[ ${#kept[@]} -eq 0 ]]; then
    echo "no usable samples out of $attempted attempt(s) ($failed failed; a successful first run would have been discarded as warm-up) — see warnings above"
    return
  fi
  local med p
  med="$(perf_stats median "${kept[@]}")"
  p="$(perf_stats p90 "${kept[@]}")"
  echo "n=${#kept[@]} (of $attempted attempts: 1 discarded as warm-up, $failed failed)"
  echo "median: ${med}${unit}   p90: ${p}${unit}"
}

main() {
  check_preconditions

  echo "--- cold start: $PACKAGE, $RUNS attempts ---" >&2
  local cold_samples=()
  for (( i = 1; i <= RUNS; i++ )); do
    local s
    if s="$(cold_start_once "$PACKAGE")"; then
      cold_samples+=("$s")
    else
      echo "WARNING: cold-start attempt $i failed; skipping" >&2
    fi
  done

  echo "--- tap-to-audio (deep link -> PLAYING): $PACKAGE, $RUNS attempts ---" >&2
  local tap_samples=()
  for (( i = 1; i <= RUNS; i++ )); do
    local s
    if s="$(tap_to_audio_once "$PACKAGE" "$VIDEO_ID")"; then
      tap_samples+=("$s")
    else
      echo "WARNING: tap-to-audio attempt $i failed or timed out; skipping" >&2
    fi
  done

  echo "--- scroll jank on Home (10 swipes each): $PACKAGE, $RUNS attempts ---" >&2
  local jank_samples=()
  for (( i = 1; i <= RUNS; i++ )); do
    local s
    if s="$(jank_once "$PACKAGE")"; then
      jank_samples+=("$s")
    else
      echo "WARNING: jank attempt $i failed; skipping" >&2
    fi
  done

  echo
  echo "Package: $PACKAGE"
  report_metric "Cold start" "ms" "$RUNS" "${cold_samples[@]}"
  report_metric "Tap-to-audio (deep link to PLAYING)" "ms" "$RUNS" "${tap_samples[@]}"
  report_metric "Scroll jank on Home" "% janky frames" "$RUNS" "${jank_samples[@]}"
}

main

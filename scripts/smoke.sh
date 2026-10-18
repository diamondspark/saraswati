#!/usr/bin/env bash
# Device smoke test for one installed Saraswati build (Task 10).
#
# Usage:
#   scripts/smoke.sh [-s SERIAL] [--skip-playback] <apk> <package> [video_id]
#
#   <apk>       path to the APK to install (debug or the locally-signed
#               release-like build — see docs/device-checks.md)
#   <package>   applicationId of that APK, e.g. dev.diamondspark.saraswati
#               or dev.diamondspark.saraswati.debug
#   [video_id]  YouTube Music video id for the signed-in deep-link check
#               (default: dQw4w9WgXcQ)
#
# Checks, in order, exiting non-zero (and stopping) on the first failure:
#   1. install        adb install -r -d <apk>
#   2. grant           pm grant <package> android.permission.POST_NOTIFICATIONS
#   3. launch           adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1
#   4. alive            process still running 8s after launch
#   5. no crash         adb logcat -d -b crash is empty for this run
#   6. permissions      requested permissions (dumpsys package) == the allowlist
#   7. no ALLOW_BACKUP  dumpsys package's flags do not include ALLOW_BACKUP
#   8. playback         (only unless --skip-playback) deep-link to
#                       music.youtube.com/watch?v=<video_id>, poll
#                       `dumpsys media_session` for PLAYING via Task 10's
#                       own scripts/smoke_helpers.py::parse_playing_state,
#                       within 20s
#
# Check 8 needs a signed-in account — never hand credentials to this
# script. Pass --skip-playback when running on a device/build that is not
# signed in (e.g. the emulator, where no account can sign in at all); the
# brief's human checkpoint (docs/pending-human-steps.md) runs this script
# WITHOUT --skip-playback, on the Pixel, after signing in through the app's
# own UI.
#
# Device selection honors $ANDROID_SERIAL (adb reads it natively) or -s;
# this script never hard-codes a serial (constraints.md R2).

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SMOKE_HELPERS_PY="$SCRIPT_DIR/smoke_helpers.py"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
MANIFEST_ALLOWLIST_JSON="$REPO_ROOT/security/manifest-allowlist.json"

DEFAULT_VIDEO_ID="dQw4w9WgXcQ"
PLAYBACK_TIMEOUT_S=20
ALIVE_WAIT_S=8

usage() {
  cat <<'EOF'
usage: smoke.sh [-s SERIAL] [--skip-playback] <apk> <package> [video_id]

  -s SERIAL         adb device serial (overrides $ANDROID_SERIAL; never
                     hard-code the Pixel's serial in a committed invocation)
  --skip-playback    skip check 8 (signed-in deep-link -> PLAYING); use this
                     on a device/build that cannot sign in (e.g. the
                     emulator)
  <apk>              path to the APK to install
  <package>          applicationId of that APK
  [video_id]         YouTube Music video id for check 8
                      (default: dQw4w9WgXcQ)

Exits non-zero on the first failed check. See the header comment in this
file for the full, ordered check list.
EOF
}

die() {
  echo "smoke.sh: $*" >&2
  exit 1
}

# --- argument parsing (no device access below this point) -----------------

ADB_SERIAL="${ANDROID_SERIAL:-}"
SKIP_PLAYBACK=0
POSITIONAL=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -s) [[ $# -ge 2 ]] || die "-s needs an argument"; ADB_SERIAL="$2"; shift 2 ;;
    --skip-playback) SKIP_PLAYBACK=1; shift ;;
    --) shift; while [[ $# -gt 0 ]]; do POSITIONAL+=("$1"); shift; done ;;
    -*) die "unknown option $1 (see -h)" ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done

if [[ ${#POSITIONAL[@]} -lt 2 || ${#POSITIONAL[@]} -gt 3 ]]; then
  usage >&2
  die "expected 2 or 3 positional arguments (<apk> <package> [video_id]), got ${#POSITIONAL[@]}"
fi

APK="${POSITIONAL[0]}"
PACKAGE="${POSITIONAL[1]}"
VIDEO_ID="${POSITIONAL[2]:-$DEFAULT_VIDEO_ID}"

[[ -f "$APK" ]] || die "no such APK: $APK"
[[ -n "$PACKAGE" ]] || die "<package> must not be empty"

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

# --- check 1: install --------------------------------------------------

check_install() {
  adb_cmd install -r -d "$APK"
}

# --- check 2: grant POST_NOTIFICATIONS ---------------------------------
# A no-op (and not a failure) on API < 33, where POST_NOTIFICATIONS isn't a
# runtime permission `pm grant` recognizes; `pm` reports that as "Unknown
# permission" on stderr rather than succeeding, so that specific message is
# treated as an informational skip, not a check failure.

check_grant_notifications() {
  local out
  if out="$(adb_cmd shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS 2>&1)"; then
    return 0
  fi
  if [[ "$out" == *"Unknown permission"* ]]; then
    echo "    NOTE: device has no POST_NOTIFICATIONS runtime permission (API < 33); skipping grant" >&2
    return 0
  fi
  echo "    $out" >&2
  return 1
}

# --- check 3: launch via monkey -----------------------------------------

check_launch() {
  local out
  out="$(adb_cmd shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 2>&1)" || {
    echo "    $out" >&2
    return 1
  }
  if [[ "$out" != *"Events injected: 1"* ]]; then
    echo "    $out" >&2
    return 1
  fi
}

# --- check 4: process alive after ALIVE_WAIT_S seconds ------------------

check_alive() {
  sleep "$ALIVE_WAIT_S"
  local pid
  pid="$(adb_cmd shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')"
  [[ -n "$pid" ]]
}

# --- check 5: crash buffer empty ----------------------------------------
# The crash buffer is cleared right after install (before launch) so a
# stale crash from a previous run can't produce a false pass or fail here.

clear_crash_log() {
  adb_cmd logcat -b crash -c
}

check_no_crash() {
  local out
  out="$(adb_cmd logcat -d -b crash 2>/dev/null)"
  if [[ -n "$out" ]]; then
    echo "$out" | sed 's/^/    /' >&2
    return 1
  fi
}

# --- checks 6 & 7: dumpsys package (requested permissions, ALLOW_BACKUP) --

dump_package() {
  adb_cmd shell dumpsys package "$PACKAGE" 2>/dev/null
}

# Prints the "requested permissions:" block's permission names, one per
# line, trimmed. That AOSP dumpsys section lists bare permission names
# until the next section header (a line containing ':').
extract_requested_permissions() {
  awk '
    /requested permissions:/ { in_block = 1; next }
    in_block && /:/ { in_block = 0 }
    in_block {
      gsub(/^[ \t]+|[ \t\r]+$/, "", $0)
      if ($0 != "") print $0
    }
  '
}

# The allowlist (security/manifest-allowlist.json, Task 4) was snapshotted
# against the release applicationId. The one package-scoped entry
# (DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, auto-generated by the Android
# Gradle plugin as "${applicationId}.<name>") is rebuilt here against
# whichever <package> this run is actually checking (debug or release-like)
# instead of being read verbatim, so this check works unmodified for both.
expected_permissions() {
  python3 -I -c '
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
allowlist_package = data["package"]
suffix = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
for perm in data["permissions"]:
    if perm == allowlist_package + suffix:
        print(sys.argv[2] + suffix)
    else:
        print(perm)
' "$MANIFEST_ALLOWLIST_JSON" "$PACKAGE" | sort -u
}

check_permissions() {
  local dump expected actual diff_out
  dump="$(dump_package)"
  expected="$(expected_permissions)"
  actual="$(printf '%s' "$dump" | extract_requested_permissions | sort -u)"
  if [[ "$expected" != "$actual" ]]; then
    echo "    expected (from $MANIFEST_ALLOWLIST_JSON):" >&2
    echo "$expected" | sed 's/^/      /' >&2
    echo "    actual (from dumpsys package $PACKAGE):" >&2
    echo "$actual" | sed 's/^/      /' >&2
    return 1
  fi
}

check_no_allow_backup() {
  local dump
  dump="$(dump_package)"
  if printf '%s' "$dump" | grep -E '^\s*flags=\[[^]]*\bALLOW_BACKUP\b[^]]*\]' >/dev/null; then
    printf '%s' "$dump" | grep -E '^\s*flags=\[' | sed 's/^/    /' >&2
    return 1
  fi
}

# --- check 8: signed-in deep-link to PLAYING ----------------------------

check_playback() {
  local start_ms now_ms deadline_ms dump result
  if ! adb_cmd shell am start -a android.intent.action.VIEW \
      -d "https://music.youtube.com/watch?v=${VIDEO_ID}" "$PACKAGE" >/dev/null 2>&1; then
    echo "    deep link 'am start' failed for $PACKAGE" >&2
    return 1
  fi
  start_ms="$(date +%s%3N)"
  deadline_ms=$(( start_ms + PLAYBACK_TIMEOUT_S * 1000 ))
  while true; do
    dump="$(adb_cmd shell dumpsys media_session 2>/dev/null)"
    result="$(printf '%s' "$dump" | python3 -I "$SMOKE_HELPERS_PY" is-playing "$PACKAGE")"
    if [[ "$result" == "true" ]]; then
      return 0
    fi
    now_ms="$(date +%s%3N)"
    if (( now_ms >= deadline_ms )); then
      echo "    timed out after ${PLAYBACK_TIMEOUT_S}s waiting for $PACKAGE to reach PLAYING" >&2
      echo "    (did you sign in first? this check needs a real, signed-in YouTube account)" >&2
      return 1
    fi
    sleep 0.5
  done
}

# --- driver --------------------------------------------------------------

run_check() {
  local name="$1"; shift
  echo "--- $name ---"
  if "$@"; then
    echo "[PASS] $name"
    return 0
  else
    echo "[FAIL] $name"
    return 1
  fi
}

main() {
  echo "smoke.sh: apk=$APK package=$PACKAGE video_id=$VIDEO_ID skip_playback=$SKIP_PLAYBACK serial=${ADB_SERIAL:-<default>}"

  run_check "1. install"                      check_install    || exit 1
  clear_crash_log
  run_check "2. grant POST_NOTIFICATIONS"      check_grant_notifications || exit 1
  run_check "3. launch via monkey"             check_launch      || exit 1
  run_check "4. process alive after ${ALIVE_WAIT_S}s" check_alive || exit 1
  run_check "5. crash buffer empty"            check_no_crash    || exit 1
  run_check "6. requested permissions == allowlist" check_permissions || exit 1
  run_check "7. no ALLOW_BACKUP flag"          check_no_allow_backup || exit 1

  if [[ "$SKIP_PLAYBACK" -eq 1 ]]; then
    echo "--- 8. signed-in deep-link to PLAYING ---"
    echo "[SKIP] 8. signed-in deep-link to PLAYING (--skip-playback)"
  else
    run_check "8. signed-in deep-link to PLAYING" check_playback || exit 1
  fi

  echo "smoke.sh: all checks passed for $PACKAGE"
}

main

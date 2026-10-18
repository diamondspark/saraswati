#!/usr/bin/env bash
# Zipalign and sign unsigned release APKs with apksigner.
#
# Shared by saraswati-release.yml's environment-gated `sign` job (the real
# release key) and saraswati-ci.yml's `release-dry-run` job (a throwaway
# keystore generated inside that job), so the dry run exercises exactly the
# commands a real release runs.
#
# Usage:
#   KS_PASS=... KEY_PASS=... KEY_ALIAS=... \
#     scripts/sign_release.sh --keystore <keystore> \
#       --in <unsigned.apk> --out <signed.apk> [--in <unsigned2.apk> --out <signed2.apk> ...]
#
# The passwords are read from the KS_PASS / KEY_PASS environment variables
# by apksigner itself (`env:` form), so they never appear on a command line.
# zipalign and apksigner must be on PATH (Android build-tools).
#
# Exit codes: 0 every APK signed, 1 a signing step failed, 2 usage error.

set -euo pipefail

usage() {
  echo "usage: KS_PASS=... KEY_PASS=... KEY_ALIAS=... $0 --keystore <ks> --in <apk> --out <apk> [--in <apk> --out <apk> ...]" >&2
  exit 2
}

keystore=""
inputs=()
outputs=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --keystore) [ "$#" -ge 2 ] || usage; keystore="$2"; shift 2 ;;
    --in) [ "$#" -ge 2 ] || usage; inputs+=("$2"); shift 2 ;;
    --out) [ "$#" -ge 2 ] || usage; outputs+=("$2"); shift 2 ;;
    *) echo "sign_release.sh: unknown argument: $1" >&2; usage ;;
  esac
done

if [ -z "$keystore" ] || [ "${#inputs[@]}" -eq 0 ] || [ "${#inputs[@]}" -ne "${#outputs[@]}" ]; then
  usage
fi
if [ ! -f "$keystore" ]; then
  echo "sign_release.sh: keystore not found: $keystore" >&2
  exit 2
fi
for var in KS_PASS KEY_PASS KEY_ALIAS; do
  if [ -z "${!var:-}" ]; then
    echo "sign_release.sh: $var is not set" >&2
    exit 2
  fi
done
for cmd in zipalign apksigner; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "sign_release.sh: $cmd not on PATH (add Android build-tools)" >&2
    exit 2
  fi
done

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

for i in "${!inputs[@]}"; do
  in="${inputs[$i]}"
  out="${outputs[$i]}"
  if [ ! -f "$in" ]; then
    echo "sign_release.sh: input APK not found: $in" >&2
    exit 2
  fi
  aligned="$work_dir/aligned-$i.apk"
  zipalign -p 4 "$in" "$aligned"
  apksigner sign \
    --ks "$keystore" \
    --ks-pass env:KS_PASS \
    --ks-key-alias "$KEY_ALIAS" \
    --key-pass env:KEY_PASS \
    --out "$out" \
    "$aligned"
  echo "sign_release.sh: signed $in -> $out"
done

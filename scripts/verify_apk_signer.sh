#!/usr/bin/env bash
# Check that each APK's signer certificate SHA-256 equals an expected digest.
#
# Shared by saraswati-release.yml's `sign` job (expected digest: the
# committed security/release-cert.sha256) and saraswati-ci.yml's
# `release-dry-run` job (expected digest: a throwaway keystore's cert, plus
# a deliberate wrong-key comparison that must fail with exit code 1).
#
# Usage:
#   scripts/verify_apk_signer.sh --expected <sha256-hex> <apk> [<apk> ...]
#
# apksigner must be on PATH (Android build-tools).
#
# Exit codes: 0 every APK matches, 1 a signer mismatch, 2 usage error or a
# digest that could not be read at all (kept distinct from 1 so a caller
# testing the mismatch path can tell "rejected the wrong key" apart from
# "couldn't read anything").

set -euo pipefail

usage() {
  echo "usage: $0 --expected <sha256-hex> <apk> [<apk> ...]" >&2
  exit 2
}

[ "$#" -ge 3 ] && [ "$1" = "--expected" ] || usage
expected="$(printf '%s' "$2" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
shift 2

if ! printf '%s' "$expected" | grep -qE '^[0-9a-f]{64}$'; then
  echo "verify_apk_signer.sh: expected digest is not 64 hex chars: '$expected'" >&2
  exit 2
fi
if ! command -v apksigner >/dev/null 2>&1; then
  echo "verify_apk_signer.sh: apksigner not on PATH (add Android build-tools)" >&2
  exit 2
fi

# apksigner's label for this line varies by build-tools version and by the
# signature scheme it reports under: 37.x prints "V3.0 Signer: certificate
# SHA-256 digest: <hex>", 36.x prints "Signer #1 certificate SHA-256 digest:
# <hex>". Match only the common "certificate SHA-256 digest:" suffix and take
# the first hit (every scheme reports the same signer).
signer_digest() {
  sed -n 's/^.*certificate SHA-256 digest: *//p' \
    | head -1 \
    | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]'
}

rc=0
for apk in "$@"; do
  if [ ! -f "$apk" ]; then
    echo "verify_apk_signer.sh: APK not found: $apk" >&2
    exit 2
  fi
  certs="$(apksigner verify --print-certs "$apk")" || {
    echo "verify_apk_signer.sh: apksigner verify failed on $apk" >&2
    exit 2
  }
  actual="$(printf '%s\n' "$certs" | signer_digest)"
  if [ -z "$actual" ]; then
    echo "verify_apk_signer.sh: could not read a signer SHA-256 digest from $apk" >&2
    exit 2
  fi
  if [ "$actual" != "$expected" ]; then
    echo "verify_apk_signer.sh: signer mismatch on $apk: expected $expected, got $actual" >&2
    rc=1
    continue
  fi
  echo "$apk: signer verified ($actual)"
done
exit "$rc"

#!/usr/bin/env bash
# Installs a pinned gitleaks release into $RUNNER_TEMP for the CI secret
# scan (saraswati-ci.yml). There is no gitleaks GitHub Action in this repo's
# allowlist (Task 1 restricts Actions to github-owned actions and
# gradle/actions/*), so this plain script downloads the linux/x64 binary
# release directly and installs it.
#
# The expected SHA-256 below is copied from gitleaks' own published
# "gitleaks_<version>_checksums.txt" release asset, not fetched at runtime:
# a compromised or mirrored download still has to match a hash that was
# pinned ahead of time, so trust runs through this file, not through
# whatever a run happens to receive over the network.
#
# Usage: scripts/install-gitleaks.sh   (meant to run on a GitHub Actions
# runner with $RUNNER_TEMP set; installs "$RUNNER_TEMP/gitleaks")
set -euo pipefail

GITLEAKS_VERSION="8.30.1"
GITLEAKS_ARCHIVE="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
GITLEAKS_URL="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/${GITLEAKS_ARCHIVE}"
# SHA-256 of gitleaks_8.30.1_linux_x64.tar.gz, from gitleaks/gitleaks
# release v8.30.1's published gitleaks_8.30.1_checksums.txt.
GITLEAKS_SHA256="551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"

: "${RUNNER_TEMP:?RUNNER_TEMP must be set; this script is meant to run on a GitHub Actions runner}"

workdir="$(mktemp -d "${RUNNER_TEMP}/gitleaks-install.XXXXXX")"
trap 'rm -rf "$workdir"' EXIT

archive_path="${workdir}/${GITLEAKS_ARCHIVE}"
curl --fail --silent --show-error --location --output "$archive_path" "$GITLEAKS_URL"

actual_sha256="$(sha256sum "$archive_path" | awk '{print $1}')"
if [ "$actual_sha256" != "$GITLEAKS_SHA256" ]; then
  echo "install-gitleaks.sh: checksum mismatch for $GITLEAKS_ARCHIVE" >&2
  echo "  expected: $GITLEAKS_SHA256" >&2
  echo "  actual:   $actual_sha256" >&2
  exit 1
fi

tar -xzf "$archive_path" -C "$workdir" gitleaks
install -m 0755 "${workdir}/gitleaks" "${RUNNER_TEMP}/gitleaks"

echo "install-gitleaks.sh: installed gitleaks ${GITLEAKS_VERSION} at ${RUNNER_TEMP}/gitleaks"

#!/usr/bin/env bash
# Saraswati signing-key ceremony.
#
# HUMAN CHECKPOINT (Task 6, Step 3): run this yourself, in your own
# terminal, on a machine you trust — never by an agent, never on a shared
# or ephemeral machine. It generates the real RSA-4096 release signing key
# (alias "saraswati") that will sign every Saraswati and Saraswati Updates
# release from now on, uploads it and its two passwords to the GitHub
# `release` environment as secrets, and makes one encrypted offline
# backup. No password is ever printed, logged, or put on a command line
# (keytool and the password files use the `:file` / stdin forms
# throughout); an agent that only reads this script's source never sees
# a real password, and this script does not run itself.
#
# After this finishes, keep TWO copies of the encrypted backup
# (release-key-backup.tar.gz.gpg) on separate offline media (two
# different USB drives, not both in the same bag), and store the gpg
# passphrase in your password manager — not next to the backup. If the
# key and both backups are ever lost, every family phone has to
# uninstall and reinstall Saraswati from a new key (see
# docs/release-runbook.md, "If the signing key is lost").
#
# Prerequisites: a JDK's `keytool` (scripts/dev-env.sh puts one on PATH),
# `gh` (run `gh auth status` first), `gpg`, `openssl`.
#
# Usage: bash scripts/key-ceremony.sh   (run from the repo root, or anywhere)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEY_DIR="$HOME/saraswati-keys"
KEYSTORE_FILE="$KEY_DIR/release.keystore"
STORE_PASS_FILE="$KEY_DIR/store-password.txt"
KEY_PASS_FILE="$KEY_DIR/key-password.txt"
CERT_FILE="$KEY_DIR/release-cert.pem"
BACKUP_TAR="$KEY_DIR/release-key-backup.tar"
BACKUP_FILE="$KEY_DIR/release-key-backup.tar.gz.gpg"
KEY_ALIAS="saraswati"
CERT_SHA256_PATH="$REPO_ROOT/security/release-cert.sha256"

for cmd in keytool gh gpg openssl base64 sha256sum tar; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "key-ceremony: required command not found on PATH: $cmd" >&2
    exit 1
  fi
done

if ! gh auth status >/dev/null 2>&1; then
  echo "key-ceremony: 'gh' is not authenticated. Run 'gh auth login' first." >&2
  exit 1
fi

if ! gh api repos/{owner}/{repo} --jq '.full_name' >/dev/null 2>&1; then
  echo "key-ceremony: could not resolve the current repository via 'gh api'." >&2
  echo "Run this from a checkout of diamondspark/saraswati." >&2
  exit 1
fi

if ! gh api repos/{owner}/{repo}/environments/release >/dev/null 2>&1; then
  echo "key-ceremony: the 'release' GitHub environment doesn't exist yet." >&2
  echo "It is created in Task 1 (gh api .../environments/release) — do that first." >&2
  exit 1
fi

if [ -e "$KEY_DIR" ]; then
  echo "key-ceremony: $KEY_DIR already exists — refusing to overwrite a possibly-existing key." >&2
  echo "If you are certain this is a fresh start (no key was ever generated here), remove it first:" >&2
  echo "  rm -rf \"$KEY_DIR\"" >&2
  exit 1
fi

if [ -s "$CERT_SHA256_PATH" ]; then
  echo "key-ceremony: $CERT_SHA256_PATH already exists and is non-empty — a release key has already been issued." >&2
  echo "Key rotation is a different procedure (see docs/release-runbook.md, 'Key rotation'); this script only runs once." >&2
  exit 1
fi

echo "== Saraswati signing-key ceremony =="
echo "Key directory: $KEY_DIR"
echo

mkdir -p -m 700 "$KEY_DIR"
chmod 700 "$KEY_DIR"

echo "Generating two random passwords (store + key) — never printed, never logged..."
( umask 077; openssl rand -base64 48 | tr -d '\n' > "$STORE_PASS_FILE" )
( umask 077; openssl rand -base64 48 | tr -d '\n' > "$KEY_PASS_FILE" )
chmod 600 "$STORE_PASS_FILE" "$KEY_PASS_FILE"

echo "Generating the RSA-4096 release key (alias: $KEY_ALIAS, validity: 10000 days)..."
( umask 077
  keytool -genkeypair \
    -keystore "$KEYSTORE_FILE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=Saraswati Family" \
    -storepass:file "$STORE_PASS_FILE" \
    -keypass:file "$KEY_PASS_FILE"
)
chmod 600 "$KEYSTORE_FILE"

echo "Exporting the certificate and computing its SHA-256 digest..."
keytool -exportcert \
  -keystore "$KEYSTORE_FILE" \
  -alias "$KEY_ALIAS" \
  -storepass:file "$STORE_PASS_FILE" \
  -rfc \
  -file "$CERT_FILE"

# openssl's own fingerprint reader, not a parse of keytool's human-readable
# -printcert text (whose exact wording has changed across JDK versions) —
# the certificate itself is public information either way, so there is
# nothing sensitive in reading it back this way.
CERT_SHA256="$(openssl x509 -in "$CERT_FILE" -noout -fingerprint -sha256 \
  | sed -n 's/^.*Fingerprint=//p' \
  | tr -d ':' \
  | tr '[:upper:]' '[:lower:]')"

if ! printf '%s' "$CERT_SHA256" | grep -qE '^[0-9a-f]{64}$'; then
  echo "key-ceremony: could not extract a 64-char lowercase hex SHA-256 from the certificate" >&2
  exit 1
fi

printf '%s\n' "$CERT_SHA256" > "$CERT_SHA256_PATH"
echo "Wrote $CERT_SHA256_PATH"
echo "Certificate SHA-256 (public): $CERT_SHA256"
echo

echo "Uploading secrets to the GitHub 'release' environment..."
echo "(piped from a file or base64 — never placed on a command line, never printed)"
base64 -w0 "$KEYSTORE_FILE" | gh secret set RELEASE_KEYSTORE_B64 --env release
gh secret set RELEASE_KEYSTORE_PASSWORD --env release < "$STORE_PASS_FILE"
printf '%s' "$KEY_ALIAS" | gh secret set RELEASE_KEY_ALIAS --env release
gh secret set RELEASE_KEY_PASSWORD --env release < "$KEY_PASS_FILE"
echo "Uploaded: RELEASE_KEYSTORE_B64, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD"
echo

echo "Creating ONE encrypted backup archive. You will be prompted for a gpg passphrase"
echo "TWICE below: once now to set it (gpg asks you to confirm it by typing it again),"
echo "and once more right after, to prove the backup actually decrypts before anything"
echo "real gets deleted. Choose a strong passphrase and save it in your password"
echo "manager now, before you forget."
echo

# The one file that bundles all four secrets together — the single most
# sensitive artifact this script ever writes to disk — gets the same
# umask+chmod belt-and-suspenders treatment as $KEYSTORE_FILE above,
# rather than relying only on $KEY_DIR's mode 700.
( umask 077
  tar -cf "$BACKUP_TAR" -C "$KEY_DIR" \
    "$(basename "$KEYSTORE_FILE")" \
    "$(basename "$STORE_PASS_FILE")" \
    "$(basename "$KEY_PASS_FILE")" \
    "$(basename "$CERT_FILE")"
)
chmod 600 "$BACKUP_TAR"
PLAINTEXT_SHA256="$(sha256sum "$BACKUP_TAR" | cut -d' ' -f1)"

( umask 077; gpg --symmetric --cipher-algo AES256 --output "$BACKUP_FILE" "$BACKUP_TAR" )
chmod 600 "$BACKUP_FILE"

# CRITICAL check: a backup that only ever gets compared against itself
# (two copies' sha256 matching each other) proves nothing if the gpg
# write itself was silently truncated or corrupted, or if the passphrase
# just typed won't actually be the one saved to the password manager —
# both copies would still match each other byte-for-byte while being
# useless. Decrypt it back out right now, before $BACKUP_TAR is
# destroyed, and refuse to proceed unless the round trip is
# byte-identical to the original.
echo
echo "Verifying the backup actually decrypts back to the exact same bytes —"
echo "enter the SAME passphrase again:"
DECRYPT_CHECK="$KEY_DIR/.backup-roundtrip-check.tar"
rm -f "$DECRYPT_CHECK"
decrypt_ok=0
if ( umask 077; gpg --decrypt --output "$DECRYPT_CHECK" "$BACKUP_FILE" ); then
  if [ -s "$DECRYPT_CHECK" ]; then
    DECRYPTED_SHA256="$(sha256sum "$DECRYPT_CHECK" | cut -d' ' -f1)"
    if [ "$DECRYPTED_SHA256" = "$PLAINTEXT_SHA256" ]; then
      decrypt_ok=1
    fi
  fi
fi
shred -u "$DECRYPT_CHECK" 2>/dev/null || rm -f "$DECRYPT_CHECK"

if [ "$decrypt_ok" -ne 1 ]; then
  echo >&2
  echo "key-ceremony: the encrypted backup did NOT verify — decrypting it back out" >&2
  echo "did not reproduce the exact original bytes (wrong passphrase entered just now," >&2
  echo "or gpg's write to the backup file was corrupted)." >&2
  echo >&2
  echo "NOTHING has been deleted. Your key material is still intact at:" >&2
  echo "  $KEYSTORE_FILE" >&2
  echo "  $STORE_PASS_FILE" >&2
  echo "  $KEY_PASS_FILE" >&2
  echo "  $CERT_FILE" >&2
  echo "  $BACKUP_TAR  (the plaintext bundle these were just packed into)" >&2
  echo >&2
  echo "Do not shred or delete any of the above. Remove the bad encrypted file and" >&2
  echo "try the backup again:" >&2
  echo "  rm -f \"$BACKUP_FILE\"" >&2
  echo "  ( umask 077; gpg --symmetric --cipher-algo AES256 --output \"$BACKUP_FILE\" \"$BACKUP_TAR\" )" >&2
  echo "  chmod 600 \"$BACKUP_FILE\"" >&2
  echo "then re-run this script (it will refuse past the ceremony's earlier steps since" >&2
  echo "$CERT_SHA256_PATH now exists — run just the two commands above by hand, then" >&2
  echo "verify with: gpg --decrypt \"$BACKUP_FILE\" | sha256sum   (expect $PLAINTEXT_SHA256)." >&2
  exit 1
fi

echo "Backup verified: it decrypts back to the exact same bytes as the original."
shred -u "$BACKUP_TAR" 2>/dev/null || rm -f "$BACKUP_TAR"

BACKUP_FINGERPRINT="$(sha256sum "$BACKUP_FILE" | cut -d' ' -f1)"

echo
echo "== Ceremony complete =="
echo "Certificate SHA-256 (now committed, public):   $CERT_SHA256"
echo "Encrypted backup file:                          $BACKUP_FILE"
echo "Encrypted backup file's own SHA-256 (use this to confirm your two copies are byte-identical):"
echo "  $BACKUP_FINGERPRINT"
echo
echo "Do these before you leave this terminal:"
echo "  1. Copy $BACKUP_FILE to TWO separate offline media (e.g. two different USB drives)."
echo "  2. On each copy, run 'sha256sum <copy>' and confirm it prints $BACKUP_FINGERPRINT."
echo "  3. Store the gpg passphrase in your password manager (not next to either copy)."
echo "  4. Only once both copies are confirmed, securely delete this directory:"
echo "       shred -u \"$KEYSTORE_FILE\" \"$STORE_PASS_FILE\" \"$KEY_PASS_FILE\" \"$CERT_FILE\" \"$BACKUP_FILE\""
echo "       rmdir \"$KEY_DIR\""
echo "  5. Commit security/release-cert.sha256 — it is public information; it does not"
echo "     contain, and cannot be used to derive, the private key."
echo
echo "Verify from here first, if you like:"
echo "  gh secret list --env release      # expect exactly the four names above"
echo "  cat \"$CERT_SHA256_PATH\"            # expect 64 lowercase hex characters"
echo "  git -C \"$REPO_ROOT\" status         # expect no keystore or password file listed"

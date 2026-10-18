#!/usr/bin/env python3
"""Builds a Saraswati update manifest (schema 1).

Standalone, stdlib-only tool (run with ``python3 -I``) that is the publish
side of the manifest `:update-core`'s `ManifestParser` strictly parses on
the client (see `update-core/src/main/kotlin/.../ManifestParser.kt` and
`DownloadPolicy.kt`). The two must agree on exactly which manifests are
well-formed: a manifest this script happily writes but the client rejects
turns a signed release into a stuck update; one the client would have
wrongly *accepted* but this script would never itself produce is the
client's problem to fix, not this script's to exploit. Every validation
below mirrors a check in the Kotlin parser, including the release-URL
dot-segment-traversal guard (DownloadPolicy.isAllowedStartUrl): a literal
``str.startswith(RELEASE_URL_PREFIX)`` passes a path containing ``../``
character-for-character, while a real HTTP client resolves it to a
different repository entirely.

Usage (library):
    from make_manifest import build_manifest
    manifest = build_manifest(channel="beta", version_code=100, ...)

Usage (CLI):
    python3 -I scripts/make_manifest.py \\
        --channel beta --version-code 100 --version-name 1.0.0 \\
        --apk-url https://github.com/diamondspark/saraswati/releases/download/v100/saraswati-v100.apk \\
        --apk-sha256 <64 lowercase hex> --apk-size 52428800 \\
        --notes "Routine update." --published-at 2026-10-17T00:00:00Z \\
        --out beta.json

Exit codes: 0 written, 1 schema violation, 2 usage error.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from urllib.parse import unquote, urlsplit

SCHEMA = 1
ALLOWED_CHANNELS = frozenset({"stable", "beta"})
MAX_NOTES_LENGTH = 1000
MAX_APK_BYTES = 104_857_600
SHA256_HEX_RE = re.compile(r"^[0-9a-f]{64}$")

RELEASE_HOST = "github.com"
RELEASE_URL_PREFIX = "https://github.com/diamondspark/saraswati/releases/download/"

_RELEASE_PATH_PREFIX_SEGMENTS: list[str] = [
    segment for segment in urlsplit(RELEASE_URL_PREFIX).path.split("/") if segment
]


def _decoded_path_segments_or_none(path: str) -> list[str] | None:
    """Splits a percent-decoded path into segments, or None if any segment
    is a dot segment (`.` or `..`). Mirrors DownloadPolicy.kt's function of
    the same shape: no resolution is attempted, a dot segment anywhere
    disqualifies the whole URL outright.
    """
    segments = [segment for segment in unquote(path).split("/") if segment]
    if any(segment in (".", "..") for segment in segments):
        return None
    return segments


def _is_allowed_release_url(url: str) -> bool:
    """Mirrors DownloadPolicy.isAllowedStartUrl: https, exactly `github.com`
    (via `.hostname`, which — like `java.net.URI.getHost()` — strips a
    userinfo trick such as `https://github.com@evil.example/x`), and a path
    strictly longer than and prefixed by the release path segments.
    """
    try:
        parts = urlsplit(url)
    except ValueError:
        return False
    if parts.scheme != "https":
        return False
    if (parts.hostname or "").lower() != RELEASE_HOST:
        return False
    segments = _decoded_path_segments_or_none(parts.path or "")
    if segments is None:
        return False
    prefix = _RELEASE_PATH_PREFIX_SEGMENTS
    if len(segments) <= len(prefix):
        return False
    return segments[: len(prefix)] == prefix


def _validate_apk_asset(apk: dict, *, label: str) -> dict:
    url = apk["url"]
    sha256 = apk["sha256"]
    size = apk["size"]

    if not _is_allowed_release_url(url):
        raise ValueError(f"{label}apk url outside the release prefix: {url}")
    if not SHA256_HEX_RE.fullmatch(sha256):
        raise ValueError(f"{label}apk sha256 is not 64 lowercase hex chars")
    if size <= 0 or size > MAX_APK_BYTES:
        raise ValueError(f"{label}apk size out of bounds: {size}")

    return {"url": url, "sha256": sha256, "size": size}


def build_manifest(
    *,
    channel: str,
    version_code: int,
    version_name: str,
    apk_url: str,
    apk_sha256: str,
    apk_size: int,
    urgent: bool,
    notes: str,
    published_at: str,
    updater: dict | None = None,
) -> dict:
    """Builds a schema-1 update manifest as a plain dict (JSON-serializable).

    Raises ValueError on any schema violation: an unknown channel, a
    non-positive versionCode, notes over 1000 chars, an apk url outside the
    release prefix (including a dot-segment traversal), a malformed
    sha256, or a size outside (0, 100 MiB]. The optional `updater` dict
    (`{"versionCode": int, "apk": {"url", "sha256", "size"}}`) is validated
    the same way when given.
    """
    if channel not in ALLOWED_CHANNELS:
        raise ValueError(f"unknown channel {channel!r}")
    if version_code <= 0:
        raise ValueError(f"invalid versionCode {version_code}")
    if len(notes) > MAX_NOTES_LENGTH:
        raise ValueError(f"notes exceed {MAX_NOTES_LENGTH} chars")

    apk = _validate_apk_asset(
        {"url": apk_url, "sha256": apk_sha256, "size": apk_size}, label=""
    )

    manifest: dict = {
        "schema": SCHEMA,
        "channel": channel,
        "versionCode": version_code,
        "versionName": version_name,
        "apk": apk,
        "urgent": urgent,
        "notes": notes,
        "publishedAt": published_at,
    }

    if updater is not None:
        updater_version_code = updater["versionCode"]
        if updater_version_code <= 0:
            raise ValueError(f"invalid updater versionCode {updater_version_code}")
        updater_apk = _validate_apk_asset(updater["apk"], label="updater ")
        manifest["updater"] = {
            "versionCode": updater_version_code,
            "apk": updater_apk,
        }

    return manifest


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="make_manifest.py")
    parser.add_argument("--channel", required=True, choices=sorted(ALLOWED_CHANNELS))
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--apk-url", required=True)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--apk-size", required=True, type=int)
    parser.add_argument("--urgent", action="store_true")
    parser.add_argument("--notes", default="")
    parser.add_argument("--published-at", required=True)
    parser.add_argument("--updater-version-code", type=int, default=None)
    parser.add_argument("--updater-apk-url", default=None)
    parser.add_argument("--updater-apk-sha256", default=None)
    parser.add_argument("--updater-apk-size", type=int, default=None)
    parser.add_argument("--out", required=True, help="path to write the manifest JSON")
    return parser


def _updater_from_args(args: argparse.Namespace) -> dict | None:
    fields = (
        args.updater_version_code,
        args.updater_apk_url,
        args.updater_apk_sha256,
        args.updater_apk_size,
    )
    if all(field is None for field in fields):
        return None
    if any(field is None for field in fields):
        raise LookupError("--updater-* flags must all be given together, or not at all")
    return {
        "versionCode": args.updater_version_code,
        "apk": {
            "url": args.updater_apk_url,
            "sha256": args.updater_apk_sha256,
            "size": args.updater_apk_size,
        },
    }


def main(argv: list[str] | None = None) -> int:
    args = _build_arg_parser().parse_args(argv)

    try:
        updater = _updater_from_args(args)
    except LookupError as exc:
        print(f"make_manifest.py: {exc}", file=sys.stderr)
        return 2

    try:
        manifest = build_manifest(
            channel=args.channel,
            version_code=args.version_code,
            version_name=args.version_name,
            apk_url=args.apk_url,
            apk_sha256=args.apk_sha256,
            apk_size=args.apk_size,
            urgent=args.urgent,
            notes=args.notes,
            published_at=args.published_at,
            updater=updater,
        )
    except ValueError as exc:
        print(f"make_manifest.py: {exc}", file=sys.stderr)
        return 1

    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)
        fh.write("\n")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

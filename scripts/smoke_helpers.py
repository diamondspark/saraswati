#!/usr/bin/env python3
"""Parsing helper for Saraswati's device smoke test (Task 10).

`parse_playing_state` reads the text `adb shell dumpsys media_session`
prints and decides whether a given package's own session (if it has one)
is in `PlaybackState.STATE_PLAYING`. It is the single, unit-tested source
of truth for that check:

- `scripts/smoke.sh` uses it for the signed-in deep-link check (brief
  constraints.md R4).
- `scripts/perf-baseline.sh`'s tap-to-audio metric imports it too, instead
  of keeping its own ad-hoc stand-in (see docs/perf-baseline.md's "Known
  gap" section, resolved by this file landing).

Also runnable as a thin CLI, matching `scripts/perf_stats.py`'s pattern, so
the bash scripts above can reach this without a second parsing
implementation in bash:

    adb shell dumpsys media_session | python3 -I scripts/smoke_helpers.py is-playing <package>
"""

from __future__ import annotations

import re
import sys

# `dumpsys media_session` prints one block per session, each starting with
# an indented `package=<name>` line and, somewhere later in the same block,
# a `state=PlaybackState {state=<int>, ...}` line — the stable shape
# `android.media.session.PlaybackState.toString()` has printed since
# Lollipop, where `STATE_PLAYING == 3`. A dump can list several sessions
# (one per app currently holding a MediaSession); a block runs from its
# `package=` line up to (not including) the next `package=` line, or to the
# end of the text.
_PACKAGE_LINE_RE = re.compile(r"^\s*package=(\S+)\s*$", re.MULTILINE)
_STATE_RE = re.compile(r"state=PlaybackState\s*\{state=(\d+)")
_STATE_PLAYING = 3


def parse_playing_state(dumpsys_text: str, package: str) -> bool:
    """Returns True if `package` has a media session in STATE_PLAYING.

    Returns False if `package` has no session anywhere in the dump, if its
    session exists but is not playing (paused, stopped, buffering, ...), or
    if a *different* package's session is the one playing — matching the
    exact package is required, "something is playing" is not enough.
    """
    lines = dumpsys_text.splitlines()
    package_line_starts = [
        i for i, line in enumerate(lines) if _PACKAGE_LINE_RE.match(line)
    ]
    matching_starts = [
        i
        for i in package_line_starts
        if _PACKAGE_LINE_RE.match(lines[i]).group(1) == package  # type: ignore[union-attr]
    ]
    for start in matching_starts:
        later_starts = [i for i in package_line_starts if i > start]
        end = later_starts[0] if later_starts else len(lines)
        block = "\n".join(lines[start:end])
        match = _STATE_RE.search(block)
        if match is not None and int(match.group(1)) == _STATE_PLAYING:
            return True
    return False


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 2 or argv[0] != "is-playing":
        print(
            "usage: smoke_helpers.py is-playing <package>  "
            "(reads 'dumpsys media_session' output on stdin)",
            file=sys.stderr,
        )
        return 2
    package = argv[1]
    playing = parse_playing_state(sys.stdin.read(), package)
    print("true" if playing else "false")
    return 0


if __name__ == "__main__":
    sys.exit(main())

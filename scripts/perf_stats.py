#!/usr/bin/env python3
"""Parsers and statistics for Saraswati's performance baseline (Task 13).

Four small, independently testable pieces of a bigger measurement pipeline
that `scripts/perf-baseline.sh` drives:

- `parse_am_start_total_time` reads the text `adb shell am start -W ...`
  prints and returns the `TotalTime` field (milliseconds), used for the cold
  start budget.
- `parse_gfxinfo_janky_percent` reads the text `adb shell dumpsys gfxinfo
  <package>` prints (after a `... reset`) and returns the `Janky frames`
  percentage, used for the scroll-jank budget.
- `median` and `p90` turn a list of per-run samples (several cold starts,
  several jank measurements, ...) into the two numbers the budgets in
  `docs/perf-baseline.md` are written against.

Also runnable as a CLI so `perf-baseline.sh` (bash) can reach these without
duplicating the parsing logic in two languages:

    python3 -I perf_stats.py median 3 1 2
    python3 -I perf_stats.py p90 1 2 3 4 5 6 7 8 9 10
    adb shell am start -W pkg/.Activity | python3 -I perf_stats.py am-start-total-time
    adb shell dumpsys gfxinfo pkg | python3 -I perf_stats.py gfxinfo-janky-percent
"""

from __future__ import annotations

import math
import re
import sys

_TOTAL_TIME_RE = re.compile(r"^TotalTime:\s*(\d+)\s*$", re.MULTILINE)
_WAIT_TIME_RE = re.compile(r"^WaitTime:\s*(\d+)\s*$", re.MULTILINE)
_JANKY_PERCENT_RE = re.compile(r"Janky frames:\s*\d+\s*\(([\d.]+)%\)")


def parse_am_start_total_time(out: str) -> int:
    """Returns the `TotalTime` (ms) from `adb shell am start -W` output.

    `am start -W` prints a block such as::

        Status: ok
        LaunchState: COLD
        Activity: dev.diamondspark.saraswati/com.metrolist.music.MainActivity
        TotalTime: 545
        WaitTime: 571
        Complete

    `TotalTime` is the activity's own measured launch time (from the start
    of the launch to the first frame), which is what the cold-start budget
    in the design spec is measured against.
    """
    match = _TOTAL_TIME_RE.search(out)
    if match is None:
        # Android 17 on the Pixel prints no TotalTime line at all, only WaitTime (the same launch measured from
        # the am command until the activity reports drawn). Fall back to it rather than fail the whole run.
        match = _WAIT_TIME_RE.search(out)
    if match is None:
        raise ValueError("no 'TotalTime: <ms>' or 'WaitTime: <ms>' line found in am start -W output")
    return int(match.group(1))


def parse_gfxinfo_janky_percent(out: str) -> float:
    """Returns the janky-frame percentage from `dumpsys gfxinfo <package>`.

    The profile section of that dump contains a line such as::

        Total frames rendered: 318
        Janky frames: 12 (4.80%)

    This returns the parenthesised percentage (`4.8`), which is what the
    scroll-jank budget (< 1% janky frames) is measured against.
    """
    match = _JANKY_PERCENT_RE.search(out)
    if match is None:
        raise ValueError("no 'Janky frames: N (P%)' line found in gfxinfo output")
    return float(match.group(1))


def median(values: list[float]) -> float:
    """Returns the median of `values` (average of the two middle values on
    an even-length list). Raises ValueError on an empty list."""
    if not values:
        raise ValueError("median of an empty sequence")
    ordered = sorted(values)
    n = len(ordered)
    mid = n // 2
    if n % 2 == 1:
        return ordered[mid]
    return (ordered[mid - 1] + ordered[mid]) / 2


def p90(values: list[float]) -> float:
    """Returns the 90th percentile of `values` using the nearest-rank
    method: the value at rank ceil(0.9 * n) in the sorted list (1-indexed).
    Raises ValueError on an empty list.

    For `n=10`, rank = ceil(9.0) = 9, i.e. the 9th-smallest of 10 values.
    """
    if not values:
        raise ValueError("p90 of an empty sequence")
    ordered = sorted(values)
    n = len(ordered)
    rank = max(1, min(n, math.ceil(0.9 * n)))
    return ordered[rank - 1]


def _format_number(value: float) -> str:
    # %g prints "2" for 2.0 and "4.8" for 4.8, matching how these numbers
    # are quoted in the brief and in docs/perf-baseline.md.
    return "%g" % value


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if not argv:
        print(
            "usage: perf_stats.py {median|p90} V [V ...]"
            " | {am-start-total-time|gfxinfo-janky-percent} (reads stdin)",
            file=sys.stderr,
        )
        return 2
    command, rest = argv[0], argv[1:]
    try:
        if command == "median":
            print(_format_number(median([float(v) for v in rest])))
        elif command == "p90":
            print(_format_number(p90([float(v) for v in rest])))
        elif command == "am-start-total-time":
            print(parse_am_start_total_time(sys.stdin.read()))
        elif command == "gfxinfo-janky-percent":
            print(_format_number(parse_gfxinfo_janky_percent(sys.stdin.read())))
        else:
            print(f"perf_stats.py: unknown command {command!r}", file=sys.stderr)
            return 2
    except ValueError as exc:
        print(f"perf_stats.py: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

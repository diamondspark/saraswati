#!/usr/bin/env python3
"""MobSF scan report reader (Task 10).

Reads MobSF's "App Security Scorecard" API response — the JSON body of
`POST /api/v1/scorecard`, produced by MobSF's own `appsec_dashboard()`
(`mobsf/StaticAnalyzer/views/common/appsec.py` in the MobSF source) — rather
than the much larger raw `report_json`. The scorecard already normalizes
every scanner (manifest, code, binary, network, permissions, trackers,
secrets, certificate) into five severity buckets:

    {"high": [...], "warning": [...], "info": [...],
     "secure": [...], "hotspot": [...]}

where each entry is `{"title": ..., "description": ..., "section": ...}`.
Re-deriving that bucketing ourselves from the raw per-scanner report would
duplicate logic MobSF already maintains, and would drift every time MobSF
changes a scanner's internal severity vocabulary (manifest findings use
`severity`, permissions use `status`, code findings nest it under
`metadata.severity`, etc.) — reading the bucket MobSF already computes
does not.

Standalone, stdlib-only tool (run with ``python3 -I``), matching every
other gate under `security/` and `scripts/` in this repo.

Usage:
    python3 -I scripts/mobsf_scan.py --report scorecard.json --baseline security/mobsf-baseline.json

Report-only right now (Task 10 Steps 1, 2 and 5): this CLI always exits 0,
whether or not it finds new High findings. Steps 3-4 are deferred to a
later round once a human can look at a real MobSF report: Step 3 triages
the first report into `security/mobsf-baseline.json` (one line per accepted
title in `docs/security-audit.md`), Step 4 then adds a gating mode to this
CLI and switches the CI job to use it.
"""

from __future__ import annotations

import argparse
import json
import sys


def high_findings(report: dict) -> list[str]:
    """Return the titles of every High-severity finding in a MobSF scorecard report.

    `report` is the parsed JSON body of MobSF's `POST /api/v1/scorecard`
    response. Missing or malformed shapes (not a dict, no "high" list, a
    finding without a string "title") are treated as "no finding" rather
    than raising: this feeds a report-only CI step that must never itself
    crash the job responsible for uploading the report as an artifact.
    """
    high = report.get("high")
    if not isinstance(high, list):
        return []
    titles: list[str] = []
    for finding in high:
        if isinstance(finding, dict):
            title = finding.get("title")
            if isinstance(title, str) and title:
                titles.append(title)
    return titles


def new_findings(report: dict, baseline: dict) -> list[str]:
    """Return `high_findings(report)` titles not recorded in `baseline`.

    `baseline` is the parsed contents of `security/mobsf-baseline.json`:
    `{"baselined_titles": [...]}`. Each baselined title's one-line reason
    (fixed upstream vs. an accepted false positive) is recorded in
    `docs/security-audit.md`, not here — see Task 10 Step 3, not done yet;
    the baseline shipped with this commit is empty.
    """
    baselined = baseline.get("baselined_titles")
    if not isinstance(baselined, list):
        baselined = []
    baselined_titles = {title for title in baselined if isinstance(title, str)}
    return [title for title in high_findings(report) if title not in baselined_titles]


def _load_json(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        raise ValueError(f"expected a JSON object in {path}")
    return data


def _cmd_report(args: argparse.Namespace) -> int:
    """Print a findings summary and exit 0. Report-only: never fails."""
    try:
        report = _load_json(args.report)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"mobsf_scan: could not load report {args.report}: {exc}", file=sys.stderr)
        return 0

    try:
        baseline = _load_json(args.baseline)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"mobsf_scan: could not load baseline {args.baseline}: {exc}", file=sys.stderr)
        baseline = {}

    high = high_findings(report)
    new = new_findings(report, baseline)
    new_set = set(new)

    print(f"MobSF high-severity findings: {len(high)} (new, not yet baselined: {len(new)})")
    for title in high:
        marker = "NEW" if title in new_set else "baselined"
        print(f"  [{marker}] {title}")

    if new:
        print(
            "\nreport-only mode (Task 10 Steps 3-4 pending): these new "
            "findings do not fail the build. A human must triage them into "
            "security/mobsf-baseline.json (with a one-line reason recorded "
            "in docs/security-audit.md) before this job starts gating.",
        )

    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="mobsf_scan.py")
    parser.add_argument(
        "--report", required=True, help="path to the MobSF scorecard JSON (POST /api/v1/scorecard)"
    )
    parser.add_argument(
        "--baseline", required=True, help="path to security/mobsf-baseline.json"
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    return _cmd_report(args)


if __name__ == "__main__":
    sys.exit(main())

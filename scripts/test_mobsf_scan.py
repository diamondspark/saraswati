"""Unit tests for mobsf_scan.py. Stdlib only; run with:

    python3 -I -m unittest discover -s scripts -p 'test_*.py' -v
"""

from __future__ import annotations

import unittest

from mobsf_scan import high_findings, new_findings

# A small MobSF scorecard fixture (the parsed JSON body of
# `POST /api/v1/scorecard`): one High finding, one Warning, one Info — only
# the High one is a finding `high_findings` should surface.
SCORECARD_MIXED_SEVERITY = {
    "high": [
        {
            "title": "Insecure Network Traffic detected",
            "description": "cleartext HTTP allowed for example.com",
            "section": "network",
        },
    ],
    "warning": [
        {
            "title": "App can be installed on a vulnerable version of Android",
            "description": "minSdkVersion is too low",
            "section": "manifest",
        },
    ],
    "info": [
        {
            "title": "This application does not have Developer Options enabled",
            "description": "informational only",
            "section": "manifest",
        },
    ],
    "secure": [],
    "hotspot": [],
}

# Two High findings, used to check that a baselined title is dropped while
# an unbaselined one is still reported as new.
SCORECARD_TWO_HIGH = {
    "high": [
        {"title": "Found 1 critical permission(s)", "description": "...", "section": "permissions"},
        {"title": "Malicious domain found - evil.example.com", "description": "...", "section": "domains"},
    ],
    "warning": [],
    "info": [],
    "secure": [],
    "hotspot": [],
}

EMPTY_SCORECARD = {"high": [], "warning": [], "info": [], "secure": [], "hotspot": []}

BASELINE_EMPTY = {"baselined_titles": []}
BASELINE_ONE_TITLE = {"baselined_titles": ["Found 1 critical permission(s)"]}


class HighFindingsTests(unittest.TestCase):
    def test_high_findings_lists_only_high_severity(self) -> None:
        self.assertEqual(
            high_findings(SCORECARD_MIXED_SEVERITY),
            ["Insecure Network Traffic detected"],
        )

    def test_empty_report_yields_no_findings(self) -> None:
        self.assertEqual(high_findings(EMPTY_SCORECARD), [])

    def test_missing_high_key_yields_no_findings(self) -> None:
        self.assertEqual(high_findings({}), [])

    def test_malformed_high_entry_is_skipped_not_raised(self) -> None:
        report = {"high": ["not a dict", {"description": "no title field"}, {"title": 42}]}
        self.assertEqual(high_findings(report), [])


class NewFindingsTests(unittest.TestCase):
    def test_new_findings_ignores_baselined_titles(self) -> None:
        self.assertEqual(
            new_findings(SCORECARD_TWO_HIGH, BASELINE_ONE_TITLE),
            ["Malicious domain found - evil.example.com"],
        )

    def test_new_findings_reports_a_new_high_finding(self) -> None:
        self.assertEqual(
            new_findings(SCORECARD_TWO_HIGH, BASELINE_EMPTY),
            [
                "Found 1 critical permission(s)",
                "Malicious domain found - evil.example.com",
            ],
        )

    def test_empty_report_yields_no_new_findings(self) -> None:
        self.assertEqual(new_findings(EMPTY_SCORECARD, BASELINE_EMPTY), [])

    def test_missing_baselined_titles_key_treated_as_empty_baseline(self) -> None:
        self.assertEqual(
            new_findings(SCORECARD_TWO_HIGH, {}),
            [
                "Found 1 critical permission(s)",
                "Malicious domain found - evil.example.com",
            ],
        )


if __name__ == "__main__":
    unittest.main()

import unittest

from sync_report import changed_hosts, render_pr_body, summarize_commits

# A trimmed, realistic `git log` sample (default multi-line format, not
# --oneline): two ordinary commits plus a merge commit with its extra
# `Merge:` header line and a two-paragraph body, newest first the way git
# already orders them. Used by the brief's "summarize_commits parses a git
# log" fixture (task-9-brief.md Step 1).
GIT_LOG_SAMPLE = """\
commit 76fa5a78b5110674442884b6f21c0cef51cdb782
Author: Upstream Dev <dev@example.com>
Date:   Fri Oct 17 09:00:00 2026 +0000

    fix(innertube): restore artist, owner and byline parsing regressions

commit ec8fa564a1b2c3d4e5f60718293a4b5c6d7e8f9
Merge: aaaaaaa bbbbbbb
Author: Upstream Dev <dev@example.com>
Date:   Thu Oct 16 08:00:00 2026 +0000

    fix(playback): let external controllers resume after task clear

    Longer explanation of the fix, spanning more than one line, which
    summarize_commits must not mistake for a second commit's subject.

commit ecdae564a1b2c3d4e5f60718293a4b5c6d7e8aa
Author: Upstream Dev <dev@example.com>
Date:   Wed Oct 15 07:00:00 2026 +0000

    fix(sync): propagate YouTube removals without dropping local-only items
"""

# A realistic pair of source snapshots (settings.gradle.kts-shaped) used by
# the brief's "changed_hosts reports added and removed hosts" fixture: one
# host added (a new, unreviewed Maven repo), one untouched.
OLD_SOURCES = {
    "settings.gradle.kts": 'maven("https://jitpack.io")\ngoogle()\n',
    "gradle/wrapper/gradle-wrapper.properties": (
        "distributionUrl=https\\://services.gradle.org/distributions/x.zip\n"
    ),
}
NEW_SOURCES = {
    "settings.gradle.kts": (
        'maven("https://jitpack.io")\n'
        'maven("https://evil.example.com/repo")\n'
        "google()\n"
    ),
    "gradle/wrapper/gradle-wrapper.properties": (
        "distributionUrl=https\\://services.gradle.org/distributions/x.zip\n"
    ),
}


class SummarizeCommitsTest(unittest.TestCase):
    def test_parses_a_git_log(self) -> None:
        self.assertEqual(
            summarize_commits(GIT_LOG_SAMPLE),
            [
                "fix(innertube): restore artist, owner and byline parsing regressions",
                "fix(playback): let external controllers resume after task clear",
                "fix(sync): propagate YouTube removals without dropping local-only items",
            ],
        )

    def test_empty_log_is_no_commits(self) -> None:
        self.assertEqual(summarize_commits(""), [])


class ChangedHostsTest(unittest.TestCase):
    def test_reports_added_and_removed_hosts(self) -> None:
        old = {"a.txt": "https://keep.example.com and https://drop.example.com"}
        new = {"a.txt": "https://keep.example.com and https://new.example.com"}
        self.assertEqual(
            changed_hosts(old, new),
            {"added": ["new.example.com"], "removed": ["drop.example.com"]},
        )

    def test_settings_gradle_sample_reports_added_mirror_only(self) -> None:
        result = changed_hosts(OLD_SOURCES, NEW_SOURCES)
        self.assertEqual(result["added"], ["evil.example.com"])
        self.assertEqual(result["removed"], [])

    def test_no_change_is_empty_both_ways(self) -> None:
        self.assertEqual(
            changed_hosts(OLD_SOURCES, OLD_SOURCES), {"added": [], "removed": []}
        )

    def test_host_moving_between_files_is_not_reported(self) -> None:
        old = {"a.txt": "https://shared.example.com", "b.txt": ""}
        new = {"a.txt": "", "b.txt": "https://shared.example.com"}
        self.assertEqual(changed_hosts(old, new), {"added": [], "removed": []})


class RenderPrBodyTest(unittest.TestCase):
    def _render(self, workflow_changes: list[str]) -> str:
        return render_pr_body(
            commits=["fix(sync): example commit"],
            diffstat=" 1 file changed, 1 insertion(+)",
            toml_changes=['-coil = "2.4.0"', '+coil = "2.5.0"'],
            host_changes={"added": ["evil.example.com"], "removed": []},
            workflow_changes=workflow_changes,
            gate_output="python3 -I security/check_workflows.py: exit 0",
        )

    def test_includes_the_three_checklist_items(self) -> None:
        body = self._render([])
        self.assertIn("- [ ] Security review of the diff done", body)
        self.assertIn("- [ ] Dependency changes reviewed", body)
        self.assertIn("- [ ] Permission and host changes approved", body)

    def test_warns_when_workflow_files_changed(self) -> None:
        body = self._render([".github/workflows/saraswati-ci.yml"])
        self.assertIn("WARNING", body)
        self.assertIn(".github/workflows/saraswati-ci.yml", body)

    def test_omits_the_warning_when_none_changed(self) -> None:
        body = self._render([])
        self.assertNotIn("WARNING", body)


if __name__ == "__main__":
    unittest.main()

"""Policy tests for the GitHub Actions workflow gate (check_workflows.py).

Stdlib-only, with inline YAML fixtures: nothing is parsed by a YAML library
and no workflow is run. Run with:

    python3 -I -m unittest discover -s security -p 'test_*.py' -v
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from check_workflows import check_workflows, load_allowlist, load_workflow_files

ALLOWLIST = [
    "saraswati-ci.yml",
    "codeql.yml",
    "saraswati-release.yml",
    "saraswati-promote.yml",
    "saraswati-upstream-sync.yml",
]

CHECKOUT_SHA = "11bd71901bbe5b1630ceea73d27597364c9af683"
CHECKOUT_STEP = f"      - uses: actions/checkout@{CHECKOUT_SHA} # v4.2.2\n"
GITHUB_TOKEN_STEP = (
    "      - run: ./gradlew test\n"
    "        env:\n"
    "          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}\n"
)


def _workflow(
    steps: str = CHECKOUT_STEP,
    on: str = "on: push\n",
    permissions: str = "permissions:\n  contents: read\n",
) -> str:
    return (
        "name: fixture\n"
        f"{on}"
        f"{permissions}"
        "jobs:\n"
        "  build:\n"
        "    runs-on: ubuntu-24.04\n"
        "    steps:\n"
        f"{steps}"
    )


def _check(name: str, text: str) -> list[str]:
    return check_workflows({name: text}, ALLOWLIST)


class CheckWorkflowsTests(unittest.TestCase):
    def test_clean_workflow_passes(self) -> None:
        text = _workflow(CHECKOUT_STEP + GITHUB_TOKEN_STEP)
        self.assertEqual(_check("saraswati-ci.yml", text), [])

    def test_flags_unpinned_action_tag(self) -> None:
        text = _workflow("      - uses: actions/checkout@v4\n")
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["unpinned action: saraswati-ci.yml: actions/checkout@v4"],
        )

    def test_accepts_sha_pinned_action_with_comment(self) -> None:
        self.assertEqual(_check("saraswati-ci.yml", _workflow(CHECKOUT_STEP)), [])

    def test_accepts_local_action(self) -> None:
        text = _workflow("      - uses: ./.github/actions/setup\n")
        self.assertEqual(_check("saraswati-ci.yml", text), [])

    def test_flags_docker_action(self) -> None:
        text = _workflow("      - uses: docker://alpine:3.19\n")
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["docker action not allowed: saraswati-ci.yml: docker://alpine:3.19"],
        )

    def test_flags_missing_permissions(self) -> None:
        text = _workflow(CHECKOUT_STEP, permissions="")
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["missing top-level permissions: saraswati-ci.yml"],
        )

    def test_job_level_permissions_do_not_satisfy_top_level_requirement(self) -> None:
        text = (
            "name: fixture\n"
            "on: push\n"
            "jobs:\n"
            "  build:\n"
            "    permissions:\n"
            "      contents: read\n"
            "    runs-on: ubuntu-24.04\n"
            "    steps:\n"
            f"{CHECKOUT_STEP}"
        )
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["missing top-level permissions: saraswati-ci.yml"],
        )

    def test_flags_pull_request_target(self) -> None:
        text = _workflow(
            CHECKOUT_STEP,
            on="on:\n  pull_request_target:\n    types: [opened]\n",
        )
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["pull_request_target is not allowed: saraswati-ci.yml"],
        )

    def test_flags_workflow_run(self) -> None:
        text = _workflow(
            CHECKOUT_STEP,
            on="on:\n  workflow_run:\n    workflows: [ci]\n    types: [completed]\n",
        )
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["workflow_run is not allowed: saraswati-ci.yml"],
        )

    def test_flags_secret_in_ci_workflow(self) -> None:
        text = _workflow(
            CHECKOUT_STEP
            + "      - run: echo hi\n"
            + "        env:\n"
            + "          X: ${{ secrets.SOMETHING }}\n"
        )
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["secret used outside release workflows: saraswati-ci.yml: SOMETHING"],
        )

    def test_allows_github_token_everywhere(self) -> None:
        text = _workflow(CHECKOUT_STEP + GITHUB_TOKEN_STEP)
        for name in ALLOWLIST:
            with self.subTest(name=name):
                self.assertEqual(_check(name, text), [])

    def test_allows_secrets_in_release_workflow(self) -> None:
        text = _workflow(
            CHECKOUT_STEP
            + "      - run: echo hi\n"
            + "        env:\n"
            + "          K: ${{ secrets.KEYSTORE_PASSWORD }}\n"
        )
        for name in ("saraswati-release.yml", "saraswati-promote.yml"):
            with self.subTest(name=name):
                self.assertEqual(_check(name, text), [])

    def test_flags_unexpected_file(self) -> None:
        self.assertEqual(
            check_workflows({"rogue.yml": _workflow()}, ALLOWLIST),
            ["unexpected workflow file: rogue.yml"],
        )

    def test_flags_pull_request_target_in_flow_list(self) -> None:
        text = _workflow(CHECKOUT_STEP, on="on: [push, pull_request_target]\n")
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["pull_request_target is not allowed: saraswati-ci.yml"],
        )

    def test_flags_unpinned_action_in_flow_mapping(self) -> None:
        text = _workflow("      - {uses: actions/checkout@v4}\n")
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["unpinned action: saraswati-ci.yml: actions/checkout@v4"],
        )

    def test_flags_short_commit_ref(self) -> None:
        text = _workflow("      - uses: actions/checkout@11bd719\n")
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["unpinned action: saraswati-ci.yml: actions/checkout@11bd719"],
        )

    def test_flags_bracket_secret_form(self) -> None:
        text = _workflow(
            CHECKOUT_STEP
            + "      - run: echo hi\n"
            + "        env:\n"
            + "          X: ${{ secrets['SOMETHING'] }}\n"
        )
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["secret used outside release workflows: saraswati-ci.yml: SOMETHING"],
        )

    def test_secret_after_hash_inside_string_is_still_flagged(self) -> None:
        text = _workflow(
            CHECKOUT_STEP + '      - run: echo " #" ${{ secrets.SOMETHING }}\n'
        )
        self.assertEqual(
            _check("saraswati-ci.yml", text),
            ["secret used outside release workflows: saraswati-ci.yml: SOMETHING"],
        )

    def test_github_token_context_name_is_case_insensitive(self) -> None:
        text = _workflow(
            CHECKOUT_STEP
            + "      - run: echo hi\n"
            + "        env:\n"
            + "          GH_TOKEN: ${{ SECRETS.github_token }}\n"
        )
        self.assertEqual(_check("saraswati-ci.yml", text), [])

    def test_full_line_comment_mentioning_trigger_is_ignored(self) -> None:
        text = _workflow(
            CHECKOUT_STEP,
            on="# never add pull_request_target or workflow_run here\non: push\n",
        )
        self.assertEqual(_check("saraswati-ci.yml", text), [])


class WorkflowFileLoaderTests(unittest.TestCase):
    def test_loads_yml_and_yaml_files_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "a.yml").write_text("x", encoding="utf-8")
            (directory / "b.yaml").write_text("y", encoding="utf-8")
            (directory / "c.YML").write_text("z", encoding="utf-8")
            (directory / "notes.txt").write_text("w", encoding="utf-8")
            self.assertEqual(
                load_workflow_files(directory),
                {"a.yml": "x", "b.yaml": "y", "c.YML": "z"},
            )

    def test_missing_directory_loads_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(load_workflow_files(Path(tmp) / "absent"), {})

    def test_allowlist_skips_blank_and_comment_lines(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "allowlist.txt"
            path.write_text("# header\n\nci.yml\n  codeql.yml  \n", encoding="utf-8")
            self.assertEqual(load_allowlist(path), ["ci.yml", "codeql.yml"])


if __name__ == "__main__":
    unittest.main()

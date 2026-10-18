#!/usr/bin/env python3
"""Report rendering for the Saraswati upstream sync bot (Task 9).

`scripts/sync_upstream.sh` drives a daily (or manually dispatched) merge of
MetrolistGroup/Metrolist's `main` into a short-lived `sync/upstream-<date>`
branch. These three functions turn the raw git/diff text that merge
produces into the pull request body a human reviews:

- `summarize_commits` turns a block of `git log` output (the default,
  multi-line format: a `commit <sha>` line, header lines, a blank line,
  then the indented subject) into a plain list of subject lines, newest
  first — the order `git log` already prints commits in.
- `changed_hosts` compares the `http(s)://` hosts mentioned in two
  generations of a set of named source files (e.g. `settings.gradle.kts`,
  `gradle/wrapper/gradle-wrapper.properties`) and reports which hosts are
  new or gone. A new host is the signal this bot cares about most:
  upstream adding a dependency or wrapper source nobody here has reviewed.
- `render_pr_body` assembles the PR body: the commit list, a diffstat, any
  `gradle/libs.versions.toml` changes, the host diff, which
  `.github/workflows/` files upstream touched (this bot keeps ours
  regardless of conflict — see `sync_upstream.sh` — but the reviewer still
  needs to know upstream tried), and the workflow-policy gate output,
  followed by a review checklist. A non-empty `workflow_changes` gets a
  prominent warning block ahead of everything else, since a workflow
  change is the highest-risk kind of upstream change this bot can see.

Also runnable as a CLI so `sync_upstream.sh` (bash) can reach these without
re-implementing git-log parsing or markdown rendering in shell:

    git log main..upstream-main | python3 -I sync_report.py summarize-commits
    python3 -I sync_report.py changed-hosts old_sources.json new_sources.json
    python3 -I sync_report.py render-pr-body < fields.json
    python3 -I sync_report.py assemble-pr-body COMMITS_LOG DIFFSTAT \\
        TOML_LINES WORKFLOW_CHANGED HOSTS_DIR GATE_OUTPUT
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

_HOST_RE = re.compile(r"https?://([A-Za-z0-9.-]+)")

# Markdown control characters escaped in untrusted upstream text (a commit
# subject, a dependency-file diff line) before it's interpolated into the
# PR body. Not a code-execution concern (this only ever reaches `gh` as a
# literal argv value) — it's so a crafted subject/line can't render as a
# heading/link/bold text or visually spoof part of the review checklist a
# human is about to read.
_MARKDOWN_SPECIAL_RE = re.compile(r"([\\`*_\[\]<>|#])")

_CHECKLIST = (
    "- [ ] Security review of the diff done",
    "- [ ] Dependency changes reviewed",
    "- [ ] Permission and host changes approved",
)


def summarize_commits(git_log: str) -> list[str]:
    """Returns each commit's subject line from `git log` default-format
    text, newest first (the order `git log` already lists commits in).

    Parses the real multi-line format (not `--oneline`): a `commit <sha>`
    line, header lines (`Author:`, `Date:`, an optional `Merge:`), a blank
    line, then the subject as the first indented line of the message
    body. Any further indented lines (a multi-paragraph body) are skipped,
    since only the subject is wanted here.
    """
    subjects: list[str] = []
    lines = git_log.splitlines()
    i, n = 0, len(lines)
    while i < n:
        if not lines[i].startswith("commit "):
            i += 1
            continue
        i += 1
        while i < n and lines[i].strip() != "":
            i += 1  # header lines: Author, Date, Merge, ...
        while i < n and lines[i].strip() == "":
            i += 1  # the blank line separating headers from the message
        if i < n:
            subjects.append(lines[i].strip())
        i += 1
    return subjects


def changed_hosts(
    old_sources: dict[str, str], new_sources: dict[str, str]
) -> dict[str, list[str]]:
    """Returns the `http(s)://` hosts that appear in `new_sources` but not
    `old_sources` (`"added"`) and vice versa (`"removed"`), both sorted.

    Each dict maps a source file's name to its full text; every host in
    every value is pooled before comparing, so a host moving from one
    file to another in the same change set is correctly reported as
    unchanged rather than as both added and removed.
    """

    def hosts_of(sources: dict[str, str]) -> set[str]:
        found: set[str] = set()
        for text in sources.values():
            found.update(_HOST_RE.findall(text))
        return found

    old_hosts = hosts_of(old_sources)
    new_hosts = hosts_of(new_sources)
    return {
        "added": sorted(new_hosts - old_hosts),
        "removed": sorted(old_hosts - new_hosts),
    }


def _escape_markdown(text: str) -> str:
    """Escapes markdown control characters in untrusted text, and
    collapses an embedded newline to a space first — a crafted multi-line
    subject could otherwise inject a markdown line of its own (a fake
    heading or a fake checklist item) regardless of character escaping.
    See the `_MARKDOWN_SPECIAL_RE` comment for why this exists at all."""
    text = text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
    return _MARKDOWN_SPECIAL_RE.sub(r"\\\1", text)


def _bulleted(items: list[str], empty: str) -> str:
    if not items:
        return empty
    return "\n".join(f"- {item}" for item in items)


def render_pr_body(
    commits: list[str],
    diffstat: str,
    toml_changes: list[str],
    host_changes: dict[str, list[str]],
    workflow_changes: list[str],
    gate_output: str,
) -> str:
    """Renders the sync PR's markdown body. See the module docstring for
    what each section is and why `workflow_changes` gets special billing.
    """
    sections: list[str] = ["## Saraswati upstream sync", ""]
    sections.append(
        "Automated merge of "
        "[MetrolistGroup/Metrolist](https://github.com/MetrolistGroup/Metrolist)"
        " `main` into this branch. This bot never merges this PR itself —"
        " a human must review and merge it."
    )
    sections.append("")

    if workflow_changes:
        sections.append("> **WARNING: upstream touched `.github/workflows/`**")
        sections.append(">")
        sections.append(
            "> This bot always keeps **our** workflow files on conflict and"
            " deletes any upstream-only workflow file, so the merge below"
            " is clean there. Review these files by hand before approving"
            " anyway — a workflow change is the highest-risk kind of"
            " upstream change:"
        )
        for name in workflow_changes:
            # Not backtick-wrapped like the host names below: a path can
            # contain a backtick itself (git/GitHub both permit it in a
            # filename), which would close an inline code span early and
            # let markdown after it render live, right inside this
            # callout. _escape_markdown alone (no code span) is what
            # actually neutralizes that, since backslash escapes don't do
            # anything *inside* a code span to begin with.
            sections.append(f"> - {_escape_markdown(name)}")
        sections.append("")

    sections.append(f"### Commits ({len(commits)})")
    sections.append("")
    sections.append(
        _bulleted([_escape_markdown(c) for c in commits], "_No commits listed._")
    )
    sections.append("")

    sections.append("### Diffstat")
    sections.append("")
    sections.append("```")
    sections.append(diffstat.strip() or "(empty diffstat)")
    sections.append("```")
    sections.append("")

    sections.append("### `gradle/libs.versions.toml` changes")
    sections.append("")
    sections.append(
        _bulleted(
            [_escape_markdown(c) for c in toml_changes],
            "_No dependency version changes detected._",
        )
    )
    sections.append("")

    sections.append("### Build-time host changes")
    sections.append("")
    added = host_changes.get("added", [])
    removed = host_changes.get("removed", [])
    if added:
        sections.append("Added:")
        sections.append(_bulleted([f"`{h}`" for h in added], ""))
    if removed:
        sections.append("Removed:")
        sections.append(_bulleted([f"`{h}`" for h in removed], ""))
    if not added and not removed:
        sections.append("_No new or removed hosts detected._")
    sections.append("")

    sections.append("### Workflow policy gate (`security/check_workflows.py`)")
    sections.append("")
    sections.append("```")
    sections.append(gate_output.strip() or "(no output)")
    sections.append("```")
    sections.append("")

    sections.append("### Review checklist")
    sections.append("")
    sections.extend(_CHECKLIST)
    sections.append("")

    return "\n".join(sections)


def _die(message: str) -> int:
    print(f"sync_report.py: {message}", file=sys.stderr)
    return 2


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if not argv:
        print(
            "usage: sync_report.py summarize-commits (reads stdin)"
            " | changed-hosts OLD.json NEW.json"
            " | render-pr-body (reads stdin JSON)"
            " | assemble-pr-body COMMITS_LOG DIFFSTAT TOML_LINES"
            " WORKFLOW_CHANGED HOSTS_DIR GATE_OUTPUT",
            file=sys.stderr,
        )
        return 2
    command, rest = argv[0], argv[1:]

    if command == "summarize-commits":
        for subject in summarize_commits(sys.stdin.read()):
            print(subject)
        return 0

    if command == "changed-hosts":
        if len(rest) != 2:
            return _die("changed-hosts needs OLD.json NEW.json")
        with open(rest[0], encoding="utf-8") as f:
            old_sources = json.load(f)
        with open(rest[1], encoding="utf-8") as f:
            new_sources = json.load(f)
        print(json.dumps(changed_hosts(old_sources, new_sources)))
        return 0

    if command == "render-pr-body":
        fields = json.load(sys.stdin)
        print(
            render_pr_body(
                commits=fields["commits"],
                diffstat=fields["diffstat"],
                toml_changes=fields["toml_changes"],
                host_changes=fields["host_changes"],
                workflow_changes=fields["workflow_changes"],
                gate_output=fields["gate_output"],
            )
        )
        return 0

    if command == "assemble-pr-body":
        if len(rest) != 6:
            return _die(
                "assemble-pr-body needs COMMITS_LOG DIFFSTAT TOML_LINES"
                " WORKFLOW_CHANGED HOSTS_DIR GATE_OUTPUT"
            )
        commits_log, diffstat_file, toml_lines_file, workflow_changed_file, hosts_dir, gate_output_file = rest
        commits = summarize_commits(Path(commits_log).read_text(encoding="utf-8"))
        diffstat = Path(diffstat_file).read_text(encoding="utf-8")
        toml_changes = [
            line
            for line in Path(toml_lines_file).read_text(encoding="utf-8").splitlines()
            if line
        ]
        workflow_changes = [
            line
            for line in Path(workflow_changed_file)
            .read_text(encoding="utf-8")
            .splitlines()
            if line
        ]
        hosts_path = Path(hosts_dir)
        old_sources = {
            p.name: p.read_text(encoding="utf-8") for p in sorted(hosts_path.glob("*.old"))
        }
        new_sources = {
            p.name: p.read_text(encoding="utf-8") for p in sorted(hosts_path.glob("*.new"))
        }
        host_changes = changed_hosts(old_sources, new_sources)
        gate_output = Path(gate_output_file).read_text(encoding="utf-8")
        print(
            render_pr_body(
                commits, diffstat, toml_changes, host_changes, workflow_changes, gate_output
            )
        )
        return 0

    return _die(f"unknown command {command!r}")


if __name__ == "__main__":
    sys.exit(main())

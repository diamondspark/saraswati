#!/usr/bin/env python3
"""GitHub Actions workflow policy checker.

Standalone, stdlib-only tool (run with ``python3 -I``) that gates every
workflow file under ``.github/workflows/`` against the Saraswati CI policy.
It matches regular expressions over the raw YAML text on purpose: the
stdlib has no YAML parser, and a regex gate that over-matches fails closed.

Policy:
- only files named in ``security/workflow-allowlist.txt`` may exist;
- every external ``uses:`` is pinned to a 40-character commit SHA
  (local ``./`` actions are exempt);
- no ``docker://`` actions;
- a top-level ``permissions:`` key is required;
- no ``pull_request_target`` or ``workflow_run`` triggers;
- ``secrets.<NAME>`` other than ``GITHUB_TOKEN`` only in release workflows.

Full-line YAML comments are ignored for the structural checks. Secret
references are scanned in the raw text, so a secret cannot hide behind a
``#`` that sits inside a quoted string.

Usage:
    python3 -I security/check_workflows.py

Exit codes: 0 pass, 1 violations found.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS_DIR = ROOT / ".github" / "workflows"
ALLOWLIST_PATH = ROOT / "security" / "workflow-allowlist.txt"

RELEASE_WORKFLOWS = frozenset({"saraswati-release.yml", "saraswati-promote.yml"})

_WORKFLOW_SUFFIXES = (".yml", ".yaml")

_SHA_RE = re.compile(r"[0-9a-f]{40}")

# Captures the value of a `uses:` key in block or flow style. The value stops
# at whitespace or a flow delimiter; a trailing `# comment` is not part of it.
_USES_RE = re.compile(
    r"""(?:^|[\s{,\[-])["']?uses["']?[ \t]*:\s*["']?([^\s"',}\]]*)""",
    re.MULTILINE | re.IGNORECASE,
)

_TOP_LEVEL_PERMISSIONS_RE = re.compile(
    r"""^["']?permissions["']?[ \t]*:""", re.MULTILINE
)

_FORBIDDEN_TRIGGERS = (
    ("pull_request_target", re.compile(r"\bpull_request_target\b", re.IGNORECASE)),
    ("workflow_run", re.compile(r"\bworkflow_run\b", re.IGNORECASE)),
)

_SECRET_RE = re.compile(
    r"""\bsecrets\s*(?:\.\s*(\w+)|\[\s*['"](\w+)['"]\s*\])""", re.IGNORECASE
)


def _active_text(text: str) -> str:
    """Blank out full-line comments, keeping line numbering intact."""
    return "\n".join(
        "" if line.lstrip().startswith("#") else line for line in text.splitlines()
    )


def _check_uses(name: str, active: str) -> list[str]:
    violations: list[str] = []
    for match in _USES_RE.finditer(active):
        value = match.group(1)
        if value.lower().startswith("docker://"):
            violations.append(f"docker action not allowed: {name}: {value}")
        elif value.startswith("./"):
            continue
        else:
            _, at_sign, ref = value.rpartition("@")
            if not at_sign or not _SHA_RE.fullmatch(ref):
                violations.append(f"unpinned action: {name}: {value}")
    return violations


def _check_secrets(name: str, raw: str, is_release: bool) -> list[str]:
    if is_release:
        return []
    violations: list[str] = []
    for match in _SECRET_RE.finditer(raw):
        secret = match.group(1) or match.group(2)
        if secret.upper() == "GITHUB_TOKEN":
            continue
        violations.append(f"secret used outside release workflows: {name}: {secret}")
    return violations


def _check_file(name: str, text: str) -> list[str]:
    active = _active_text(text)
    violations: list[str] = []
    violations.extend(_check_uses(name, active))
    if not _TOP_LEVEL_PERMISSIONS_RE.search(active):
        violations.append(f"missing top-level permissions: {name}")
    for event, pattern in _FORBIDDEN_TRIGGERS:
        if pattern.search(active):
            violations.append(f"{event} is not allowed: {name}")
    violations.extend(_check_secrets(name, text, name in RELEASE_WORKFLOWS))
    return violations


def check_workflows(files: dict[str, str], allowlist: list[str]) -> list[str]:
    """Return policy violations for workflow files; an empty list means pass.

    ``files`` maps a workflow file name to its raw YAML text.
    """
    allowed = set(allowlist)
    violations: list[str] = []
    for name in sorted(files):
        if name not in allowed:
            violations.append(f"unexpected workflow file: {name}")
        violations.extend(_check_file(name, files[name]))
    return list(dict.fromkeys(violations))


def load_workflow_files(directory: Path) -> dict[str, str]:
    """Read every `.yml` / `.yaml` file in a workflows directory.

    GitHub runs both extensions, so both must be checked; otherwise a
    `.yaml` file would sit outside the gate.
    """
    files: dict[str, str] = {}
    if not directory.is_dir():
        return files
    for path in sorted(directory.iterdir()):
        if path.is_file() and path.suffix.lower() in _WORKFLOW_SUFFIXES:
            files[path.name] = path.read_text(encoding="utf-8")
    return files


def load_allowlist(path: Path) -> list[str]:
    names: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        entry = line.strip()
        if entry and not entry.startswith("#"):
            names.append(entry)
    return names


def main() -> int:
    files = load_workflow_files(WORKFLOWS_DIR)
    allowlist = load_allowlist(ALLOWLIST_PATH)
    violations = check_workflows(files, allowlist)
    for violation in violations:
        print(violation)
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())

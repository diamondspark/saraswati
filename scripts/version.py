#!/usr/bin/env python3
"""Computes Saraswati's Android versionCode from git history.

Upstream Metrolist bumps `versionCode` by hand on every release. Saraswati
instead derives it from the total commit count on `HEAD`, so it only ever
goes up and never needs a manual edit. `app/build.gradle.kts` calls this
script's CLI via Gradle's `providers.exec` and uses the printed integer.

A shallow clone's commit count is an artifact of the clone depth, not the
real project history, so computing a versionCode from one could eventually
produce a value Android rejects as a downgrade of a real install. This
script refuses to produce a versionCode in that case.
"""

from __future__ import annotations

import os
import subprocess
import sys


def version_code(commit_count: int, is_shallow: bool) -> int:
    """Returns the versionCode for a repo with `commit_count` commits on HEAD.

    Raises ValueError if the repo is a shallow clone (its commit count does
    not reflect the real history) or has fewer than one commit.
    """
    if is_shallow:
        raise ValueError(
            "refusing to compute a versionCode from a shallow clone "
            "(its commit count is not the real project history)"
        )
    if commit_count < 1:
        raise ValueError(f"commit_count must be >= 1, got {commit_count}")
    return commit_count


def _repo_root() -> str:
    # Anchor to the repo root via this file's own location, not the caller's
    # working directory: Gradle's providers.exec may invoke this script with
    # any cwd.
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _run_git(args: list[str], cwd: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


def _git_commit_count(cwd: str) -> int:
    return int(_run_git(["rev-list", "--count", "HEAD"], cwd))


def _git_is_shallow(cwd: str) -> bool:
    return _run_git(["rev-parse", "--is-shallow-repository"], cwd) == "true"


def main(argv: list[str] | None = None) -> int:
    repo_root = _repo_root()
    try:
        commit_count = _git_commit_count(repo_root)
        is_shallow = _git_is_shallow(repo_root)
        code = version_code(commit_count, is_shallow)
    except subprocess.CalledProcessError as exc:
        stderr = exc.stderr.strip() if exc.stderr else ""
        print(f"version.py: git command failed: {stderr}", file=sys.stderr)
        return 2
    except (ValueError, FileNotFoundError) as exc:
        print(f"version.py: {exc}", file=sys.stderr)
        return 2

    print(code)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

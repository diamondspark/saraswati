#!/usr/bin/env bash
# Saraswati upstream sync bot (Task 9).
#
# Merges MetrolistGroup/Metrolist's main into a short-lived
# sync/upstream-<date> branch, flags (and reverts) any workflow-file
# tampering, and opens a pull request for a human to review. Run by
# .github/workflows/saraswati-upstream-sync.yml on a daily schedule and on
# workflow_dispatch; also safe to run by hand from a clean checkout, and
# safe to point at a scratch clone/remote for testing (see the
# SYNC_UPSTREAM_URL/SYNC_UPSTREAM_REF/SYNC_BASE_BRANCH overrides below) —
# never run it against a real checkout you care about without reading this
# header first, since on a clean merge it genuinely pushes and opens a PR.
#
# This bot NEVER merges a pull request itself, NEVER touches
# gradle/verification-metadata.xml (a changed dependency must fail strict
# verification so a person reviews it, rather than this merge silently
# inheriting upstream's own trusted checksums for a dependency bump nobody
# here has looked at), and NEVER touches a release workflow or secret.
#
# Conflict handling: .github/workflows/** always keeps OUR side — any
# upstream change there is reverted and any upstream-only file there is
# deleted, whether or not that specific path actually conflicted. The same
# "ours, unconditionally" rule applies to gradle/verification-metadata.xml.
# Nothing else is auto-resolved: a conflict anywhere else aborts the merge,
# prints an issue-style message (and best-effort opens a real GitHub issue
# — see the GITHUB_REPOSITORY note below) listing the files, and stops
# without pushing anything.
#
# Env vars:
#   GITHUB_REPOSITORY   "owner/repo" — required to push the sync branch,
#                       open the PR, open a conflict issue, or dispatch
#                       ci.yml. Left unset, the script still runs the full
#                       fetch/merge/conflict-resolution and stops cleanly
#                       (exit 0) right before anything that would touch
#                       GitHub, printing the PR body it would have used.
#   GITHUB_TOKEN        used only for `git push` (folded into an explicit
#                       x-access-token URL at the point of pushing, never
#                       persisted to .git/config) and, via GH_TOKEN, for
#                       `gh`. Never echoed. Opening a plain GitHub issue
#                       (the conflict path) needs the `issues: write`
#                       permission, which this bot's job does not request
#                       (see the brief: contents/pull-requests/actions
#                       write only) — that call is best-effort and its
#                       failure never changes this script's exit code; the
#                       stop-and-print behavior is the real safety net.
#   SYNC_UPSTREAM_URL   override for the fetched upstream repo (default:
#                       the real MetrolistGroup/Metrolist.git) — point
#                       this at a scratch clone/path to test the conflict
#                       path without touching the real repos.
#   SYNC_UPSTREAM_REF   override for the fetched branch (default: main).
#   SYNC_BASE_BRANCH    override for the branch merged into (default:
#                       main) — must already exist as "origin/<branch>".
#   SYNC_REPO_ROOT      override for which git working tree this script
#                       operates on (default: one level up from this
#                       script's own location). Point a *copy* of this
#                       script at a separate clone via this var instead of
#                       running the in-repo copy directly against it,
#                       since step 1 below replaces that clone's working
#                       tree with the base branch's content.
#

# Exit codes: 0 on "no new upstream commits", on a pushed/opened PR, and on
# a clean local merge with no GITHUB_REPOSITORY to publish to; 1 on a
# conflict this bot doesn't auto-resolve, or any other failure.

set -euo pipefail

UPSTREAM_URL="${SYNC_UPSTREAM_URL:-https://github.com/MetrolistGroup/Metrolist.git}"
UPSTREAM_REF="${SYNC_UPSTREAM_REF:-main}"
UPSTREAM_LOCAL_BRANCH="upstream-main"
BASE_BRANCH="${SYNC_BASE_BRANCH:-main}"
TOML_FILE="gradle/libs.versions.toml"
VERIFICATION_METADATA_FILE="gradle/verification-metadata.xml"
HOST_SCAN_FILES=(
  "settings.gradle.kts"
  "build.gradle.kts"
  "app/build.gradle.kts"
  "gradle/wrapper/gradle-wrapper.properties"
)
CI_WORKFLOW_FILE="saraswati-ci.yml"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# Normally the repo this script operates on is the one it ships inside of
# (one level up from its own location). SYNC_REPO_ROOT overrides that, so
# a copy of this script kept outside any clone can drive a *separate*
# scratch clone — needed because step 1 below switches that clone's
# working tree to match the base branch, and the base branch does not
# necessarily contain this script.
REPO_ROOT="${SYNC_REPO_ROOT:-$(cd -- "$SCRIPT_DIR/.." && pwd)}"
SYNC_REPORT_PY="$SCRIPT_DIR/sync_report.py"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

die() {
  echo "sync_upstream.sh: $*" >&2
  exit 1
}

cd "$REPO_ROOT"

command -v git >/dev/null || die "git not found"
command -v python3 >/dev/null || die "python3 not found"
[[ -f "$SYNC_REPORT_PY" ]] || die "sync_report.py not found at $SYNC_REPORT_PY"

git config user.name >/dev/null 2>&1 || git config user.name "saraswati-sync-bot"
git config user.email >/dev/null 2>&1 || git config user.email "saraswati-sync-bot@users.noreply.github.com"

# --- 1. make sure we're on an up-to-date local base branch -----------------

git fetch origin "$BASE_BRANCH"
git checkout -B "$BASE_BRANCH" "origin/$BASE_BRANCH"

# --- 2. fetch the real upstream repo into the upstream-main ref ------------

echo "sync_upstream.sh: fetching $UPSTREAM_REF from $UPSTREAM_URL"
git fetch "$UPSTREAM_URL" "$UPSTREAM_REF"
new_upstream_sha="$(git rev-parse FETCH_HEAD)"
git branch -f "$UPSTREAM_LOCAL_BRANCH" "$new_upstream_sha"

new_commit_count="$(git rev-list --count "$BASE_BRANCH..$UPSTREAM_LOCAL_BRANCH")"
if [[ "$new_commit_count" -eq 0 ]]; then
  echo "no new upstream commits"
  exit 0
fi
echo "sync_upstream.sh: $new_commit_count new upstream commit(s) ($UPSTREAM_LOCAL_BRANCH now at $new_upstream_sha)"

# --- 2b. skip if a sync PR from a previous run is still open ---------------
# $BASE_BRANCH only advances once a human actually merges a sync PR, so
# without this check, every day review is pending this bot would open
# another near-duplicate PR proposing the same still-unmerged upstream
# commits: a bot with contents:write/pull-requests:write flooding the
# queue with near-duplicates is exactly the kind of thing that erodes the
# "a human reviews each PR carefully" safety net the rest of this design
# relies on. Simplest fix: just skip, with a log line, and let the
# existing PR pick up any later commits once it's merged.
if [[ -n "${GITHUB_REPOSITORY:-}" ]] && command -v gh >/dev/null 2>&1; then
  export GH_TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
  # Distinguish "gh pr list succeeded and found nothing" (empty is correct:
  # proceed) from "gh pr list itself failed" (empty would be a lie: a rate
  # limit, an auth hiccup, any transient API failure must NOT be read as
  # "no PR is open," or this check fails open and recreates the exact
  # duplicate-PR-flooding risk it exists to prevent). The `if !` below
  # captures the command's own exit status, not just its (possibly empty,
  # possibly meaningless) stdout.
  if ! existing_sync_pr="$(gh pr list --repo "$GITHUB_REPOSITORY" --state open \
    --json headRefName,url \
    --jq '[.[] | select(.headRefName | startswith("sync/upstream-"))] | .[0].url // empty' \
    2>"$WORKDIR/pr_list.err")"; then
    cat "$WORKDIR/pr_list.err" >&2
    die "could not check for an already-open sync PR (gh pr list failed — see the error above); not safe to assume none is open, so stopping rather than risking a duplicate"
  fi
  if [[ -n "$existing_sync_pr" ]]; then
    echo "sync_upstream.sh: a sync PR is already open ($existing_sync_pr); skipping until it is merged or closed" >&2
    exit 0
  fi
fi

# --- 3. branch, and capture the report material before merging -------------
# Captured against the base/upstream branches directly, so the report
# reflects what upstream is proposing even if the merge is later aborted.
#
# The content diffs (as opposed to the commit list) are taken from the
# merge-base of base and upstream-main, NOT from base directly: base
# carries Saraswati's own permanent fork delta (dropped CI, removed
# mirrors, added security code, ...), and diffing straight against it
# would show that whole permanent delta as "changed" on every single
# sync, burying whatever upstream's new commits actually did. The
# merge-base is exactly the point the two histories last agreed on
# (the original pin on a first run; the last successfully-merged
# upstream commit afterwards), so diffing from there isolates just
# upstream's own new changes, the same way `$BASE_BRANCH..$UPSTREAM_LOCAL_BRANCH`
# already isolates just its new commits.

sync_branch="sync/upstream-$(date -u +%Y%m%d)"
git checkout -B "$sync_branch" "$BASE_BRANCH"

common_ancestor="$(git merge-base "$BASE_BRANCH" "$UPSTREAM_LOCAL_BRANCH")"

# Defensive check: everything from here on (the "no new commits" check
# above included) trusts that $BASE_BRANCH actually contains upstream's
# history, which only stays true if every previous sync PR was merged
# into it with a real merge commit — never squashed or rebased (see
# docs/release-runbook.md for why, and for the branch-settings change a
# human needs to make to enforce it; nothing here can enforce it from the
# GitHub Settings side). If origin/$UPSTREAM_LOCAL_BRANCH (the upstream
# tip this bot itself recorded after the last successful sync) is not an
# ancestor of today's merge-base, that assumption just broke — likely a
# squash/rebase merge — and every diff below would be silently computed
# against the wrong starting point. Fail loudly here instead.
git fetch origin "$UPSTREAM_LOCAL_BRANCH" 2>/dev/null || true
if git rev-parse --verify --quiet "refs/remotes/origin/$UPSTREAM_LOCAL_BRANCH" >/dev/null 2>&1; then
  recorded_upstream_tip="$(git rev-parse "origin/$UPSTREAM_LOCAL_BRANCH")"
  if ! git merge-base --is-ancestor "$recorded_upstream_tip" "$common_ancestor" 2>/dev/null; then
    die "ancestry check failed: origin/$UPSTREAM_LOCAL_BRANCH ($recorded_upstream_tip) is not an ancestor of merge-base($BASE_BRANCH, $UPSTREAM_LOCAL_BRANCH) ($common_ancestor). A previous upstream-sync PR was most likely merged into $BASE_BRANCH with a squash or rebase instead of a real merge commit, which breaks the ancestry this bot's \"new commits\" and diff logic both depend on. Refusing to compute a silently wrong report — a human needs to reconcile $BASE_BRANCH with $UPSTREAM_LOCAL_BRANCH by hand (and see docs/release-runbook.md on merging sync PRs) before this bot runs again."
  fi
fi

git log --no-color "$BASE_BRANCH..$UPSTREAM_LOCAL_BRANCH" >"$WORKDIR/commits.log"
git diff --no-color --stat "$common_ancestor" "$UPSTREAM_LOCAL_BRANCH" >"$WORKDIR/diffstat.txt" || true
git diff --no-color --name-only "$common_ancestor" "$UPSTREAM_LOCAL_BRANCH" -- .github/workflows \
  >"$WORKDIR/workflow_changed.txt" || true
git diff --no-color "$common_ancestor" "$UPSTREAM_LOCAL_BRANCH" -- "$TOML_FILE" \
  | grep -E '^[+-][^+-]' >"$WORKDIR/toml_lines.txt" || true

mkdir -p "$WORKDIR/hosts"
idx=0
for f in "${HOST_SCAN_FILES[@]}"; do
  safe_name="$(printf '%s' "$f" | tr '/' '_')"
  git show "$common_ancestor:$f" >"$WORKDIR/hosts/${idx}_${safe_name}.old" 2>/dev/null \
    || : >"$WORKDIR/hosts/${idx}_${safe_name}.old"
  git show "$UPSTREAM_LOCAL_BRANCH:$f" >"$WORKDIR/hosts/${idx}_${safe_name}.new" 2>/dev/null \
    || : >"$WORKDIR/hosts/${idx}_${safe_name}.new"
  idx=$((idx + 1))
done

# --- 4. attempt the merge ---------------------------------------------------

merge_ok=1
if ! git merge --no-ff --no-commit -m "merge $UPSTREAM_LOCAL_BRANCH into $sync_branch" "$UPSTREAM_LOCAL_BRANCH"; then
  merge_ok=0
  echo "sync_upstream.sh: merge produced conflicts; applying the ours-always rules" >&2
fi

# --- 5. automatic conflict rules: .github/workflows/** and
#        gradle/verification-metadata.xml always keep OUR side, whether or
#        not that specific path actually conflicted. ------------------------

if git cat-file -e "$BASE_BRANCH:.github/workflows" 2>/dev/null; then
  git checkout "$BASE_BRANCH" -- .github/workflows
fi
if [[ -d .github/workflows ]]; then
  while IFS= read -r -d '' path; do
    rel="${path#./}"
    if ! git cat-file -e "$BASE_BRANCH:$rel" 2>/dev/null; then
      rm -f -- "$rel"
      git rm -f --ignore-unmatch --quiet -- "$rel" >/dev/null 2>&1 || true
    fi
  done < <(find .github/workflows -type f -print0 2>/dev/null)
fi

if git cat-file -e "$BASE_BRANCH:$VERIFICATION_METADATA_FILE" 2>/dev/null; then
  git checkout "$BASE_BRANCH" -- "$VERIFICATION_METADATA_FILE"
else
  rm -f -- "$VERIFICATION_METADATA_FILE"
  git rm -f --ignore-unmatch --quiet -- "$VERIFICATION_METADATA_FILE" >/dev/null 2>&1 || true
fi

remaining_conflicts="$(git diff --name-only --diff-filter=U || true)"

if [[ -n "$remaining_conflicts" ]]; then
  echo "sync_upstream.sh: unresolved conflicts outside .github/workflows and $VERIFICATION_METADATA_FILE; stopping" >&2
  issue_title="Upstream sync conflict: $sync_branch"
  issue_body="$(cat <<EOF
Upstream sync hit merge conflicts this bot does not auto-resolve.

- Upstream: $UPSTREAM_URL ($UPSTREAM_REF @ $new_upstream_sha)
- Base: $BASE_BRANCH
- Attempted branch: $sync_branch (merge aborted locally; nothing was pushed)

Conflicting files (need a human to resolve by hand, then re-run the sync):
$(printf '%s\n' "$remaining_conflicts" | sed 's/^/- /')
EOF
)"
  echo "$issue_body" >&2
  git merge --abort 2>/dev/null || true
  if [[ -n "${GITHUB_REPOSITORY:-}" ]] && command -v gh >/dev/null 2>&1; then
    export GH_TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
    if ! gh issue create --repo "$GITHUB_REPOSITORY" --title "$issue_title" --body "$issue_body"; then
      echo "sync_upstream.sh: could not open a GitHub issue (Issues may be disabled on this repo, or this job's token lacks 'issues: write' — the conflict details above are the only record either way)" >&2
    fi
  else
    echo "sync_upstream.sh: GITHUB_REPOSITORY/gh not available; the message above is the only record" >&2
  fi
  exit 1
fi

git add -A
git commit -m "merge $UPSTREAM_LOCAL_BRANCH into $sync_branch" --quiet

echo "sync_upstream.sh: clean merge on $sync_branch (merge_ok=$merge_ok before the ours-always rules)"

# --- 6. render the PR body, run the workflow-policy gate on the result -----

set +e
gate_stdout="$(python3 -I security/check_workflows.py 2>&1)"
gate_exit=$?
set -e
{
  printf '%s\n' "$gate_stdout"
  echo "(python3 -I security/check_workflows.py exit code: $gate_exit)"
} >"$WORKDIR/gate_output.txt"

pr_body="$(python3 -I "$SYNC_REPORT_PY" assemble-pr-body \
  "$WORKDIR/commits.log" "$WORKDIR/diffstat.txt" "$WORKDIR/toml_lines.txt" \
  "$WORKDIR/workflow_changed.txt" "$WORKDIR/hosts" "$WORKDIR/gate_output.txt")"

if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "sync_upstream.sh: GITHUB_REPOSITORY not set; stopping before push (local-only run)" >&2
  echo "$pr_body"
  exit 0
fi

# --- 7. push, open the PR, dispatch ci.yml ----------------------------------
# The token is folded into an explicit push URL rather than a persisted
# remote (actions/checkout ran with persist-credentials: false), so it is
# never written to .git/config and never appears in `git remote -v`.

push_url="origin"
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  push_url="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
fi

git push "$push_url" "HEAD:refs/heads/$sync_branch"
# Record the upstream tip actually synced, so tomorrow's run does not
# re-propose the same upstream commits while this PR is still open.
git push "$push_url" "$UPSTREAM_LOCAL_BRANCH:refs/heads/$UPSTREAM_LOCAL_BRANCH"

if ! command -v gh >/dev/null 2>&1; then
  echo "sync_upstream.sh: gh not available; pushed $sync_branch but could not open a PR" >&2
  echo "$pr_body"
  exit 1
fi

export GH_TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
# Best-effort: creating a label needs 'issues: write', which this job does
# not request. If the label already exists (it should: create it once by
# hand, same as the repo's other labels) this is a harmless no-op.
gh label create needs-security-review --repo "$GITHUB_REPOSITORY" \
  --color B60205 \
  --description "Automated upstream sync PR; needs a security review before merge" \
  >/dev/null 2>&1 || true

pr_url=""
if pr_url="$(gh pr create --repo "$GITHUB_REPOSITORY" \
  --base "$BASE_BRANCH" --head "$sync_branch" \
  --title "Upstream sync: $sync_branch" \
  --body "$pr_body" \
  --label needs-security-review 2>"$WORKDIR/pr_create.err")"; then
  echo "sync_upstream.sh: opened $pr_url (labeled needs-security-review)"
else
  echo "sync_upstream.sh: PR create with the needs-security-review label failed, retrying without a label:" >&2
  cat "$WORKDIR/pr_create.err" >&2
  if pr_url="$(gh pr create --repo "$GITHUB_REPOSITORY" \
    --base "$BASE_BRANCH" --head "$sync_branch" \
    --title "Upstream sync: $sync_branch" \
    --body "$pr_body" 2>"$WORKDIR/pr_create_retry.err")"; then
    echo "sync_upstream.sh: opened $pr_url (without a label; create needs-security-review by hand and label it)" >&2
  else
    echo "sync_upstream.sh: PR create failed entirely; $sync_branch (and the updated $UPSTREAM_LOCAL_BRANCH ref) are already pushed with no PR behind them — open one by hand or delete the branch before the next run:" >&2
    cat "$WORKDIR/pr_create_retry.err" >&2
    echo "$pr_body"
    exit 1
  fi
fi

# Pull requests opened with the default GITHUB_TOKEN don't trigger
# workflows on their own, so the branch protection status checks never
# run unless something dispatches them explicitly.
if ! gh workflow run "$CI_WORKFLOW_FILE" --repo "$GITHUB_REPOSITORY" --ref "$sync_branch"; then
  echo "sync_upstream.sh: could not dispatch $CI_WORKFLOW_FILE on $sync_branch; dispatch it by hand" >&2
fi

echo "sync_upstream.sh: done ($pr_url)"

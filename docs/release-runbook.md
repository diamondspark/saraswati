# Saraswati release runbook

This is the operational reference for shipping a signed release to the
family. It is built incrementally across Phase 2's tasks — this file starts
as a skeleton in Task 1 and gains the signing ceremony, the build-and-tag
steps and the promote steps in Task 6.

## Repository security (Task 1)

Applied 2026-10-18 via `gh api`:

- **Actions enabled, restricted to a selected-actions allowlist:**
  `allowed_actions: "selected"`, with `github_owned_allowed: true`,
  `verified_allowed: false`, `patterns_allowed: ["gradle/actions/*"]`.
  Default workflow token permissions: `read`, and workflow runs cannot
  approve pull request reviews.
- **`release` environment:** requires the owner (`diamondspark`) as a
  reviewer before any job in that environment runs; restricted to
  deployments from the `main` branch via a custom branch policy (not the
  repo's generic protected-branches setting, since `main` isn't yet branch
  protected at the time this environment was created).
- **Fork PR approval policy:** `all_external_contributors` — a maintainer
  must approve before any workflow run triggered by a fork PR executes
  (Phase 1 ruling R12, carried into Phase 2 as ruling Q4).
- **GitHub 2FA:** the account's two-factor method could not be read back
  via `gh api user` (the API no longer exposes `two_factor_authentication`
  for this token), so the owner confirmed directly: passkey/security-key or
  authenticator-app sign-in, recovery codes stored offline, no stale
  personal access tokens. This confirmation is the hard prerequisite for
  the signing-key ceremony (Task 6) — the first step that puts a secret
  into GitHub.

## Branch protection on `main` (applied in Task 3, once real check names exist)

Task 1 fixes the exact policy now so Task 3 applies it without
re-deciding anything. Required status checks are **strict** (branch must
be up to date before merging) and name the two jobs Task 3's workflows
produce: `build-test` (from `saraswati-ci.yml`) and `analyze` (from
`codeql.yml`).

```bash
gh api -X PUT repos/diamondspark/saraswati/branches/main/protection --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["build-test", "analyze"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
```

No required PR reviewers: the owner is the sole maintainer and works
through pull requests, but the binding human gate on anything that ships
is the `release` environment's required-reviewer approval, not a second
review gate on every commit to `main`.

Verify after applying: `gh api repos/diamondspark/saraswati/branches/main/protection --jq '{strict: .required_status_checks.strict, contexts: .required_status_checks.contexts, force_pushes: .allow_force_pushes.enabled, deletions: .allow_deletions.enabled}'`.

## Upstream sync bot (Task 9): sync PRs need a real merge commit — HUMAN ACTION NEEDED

**Merge every `sync/upstream-<date>` PR with "Create a merge commit." Never
"Squash and merge" or "Rebase and merge" one of these.**

`scripts/sync_upstream.sh` decides whether there are "new upstream commits"
and scopes its whole report (diffstat, the `.github/workflows/` warning
list, the TOML/host diffs) by assuming `main`'s history actually contains
every upstream commit a previous sync PR brought in — i.e. that the PR was
merged with a real two-parent merge commit. If a sync PR is squashed or
rebased instead, the squashed commit on `main` is not a descendant of
`upstream-main`'s tip, so the next run's `git rev-list --count
base..upstream-main` and `git merge-base` calls silently land on the wrong
starting point: it could re-propose already-landed upstream commits as
"new," or render a wrong/noisy diffstat. The script now has a defensive
check for exactly this (`sync_upstream.sh`'s ancestry check, right after it
computes the merge-base) and will refuse to run rather than compute a
silently wrong report if it ever finds this broken — but by then a human
already has to reconcile `main` with `upstream-main` by hand, which this
note is here to help you avoid needing to do.

GitHub's merge button defaults to "Squash and merge," and nothing stops a
different method being picked by mistake: `main` is not yet branch
protected (`branches/main/protection` is 404 as of this writing), and
branch protection doesn't control merge methods anyway — that's a
repository-level setting. **A human needs to set this directly in GitHub:
Settings → General → Pull Requests → uncheck "Allow squash merging" and
"Allow rebase merging," leaving only "Allow merge commits" checked** (or,
narrower, leave all three enabled and just always pick "Create a merge
commit" by hand specifically for `sync/upstream-*` PRs). This is not
something this bot, or an agent working on this repo, can or should set
via `gh api` on its own initiative — it's a repo-wide policy change the
owner should make deliberately.

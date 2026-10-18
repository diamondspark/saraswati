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

## CI pipeline (Task 3)

`.github/workflows/saraswati-ci.yml` (workflow `ci`, job `build-test`)
and `.github/workflows/codeql.yml` (workflow `codeql`, job `analyze`)
build, test and gate every push/PR; `.github/dependabot.yml` covers the
`gradle` and `github-actions` ecosystems weekly. Every `uses:` is a
github-owned action or `gradle/actions/*`, pinned to a 40-character
commit SHA (Task 1's selected-actions policy allows nothing else to run
regardless of what a workflow file says).

The first real runs surfaced two environment gaps that a long-lived dev
machine's warm caches had hidden, both fixed in `phase2-pipeline`:

- **Dependency verification.** `--dependency-verification strict`
  failed on a clean Gradle user home with missing POM/module hashes
  (Guava, JUnit, kotlinx-coroutines, Groovy and Bouncy Castle BOM/parent
  metadata — not binary artifacts, not new libraries) that this
  machine's `~/.gradle` had already resolved from past work. Reproduced
  against four independent from-scratch `GRADLE_USER_HOME` directories
  and fixed by adding the missing entries to
  `gradle/verification-metadata.xml`.
- **`apkanalyzer` on PATH.** `security/apk_gate.py` invokes the bare
  `apkanalyzer` command; the runner's preinstalled Android SDK has it
  under `cmdline-tools`, but (unlike `scripts/dev-env.sh` on the dev
  machine) the runner doesn't put that directory on PATH by default.
  Fixed with an explicit PATH step in `saraswati-ci.yml`.

**Gitleaks baseline.** The full-history secret scan correctly found 16
hardcoded Google API keys already in this history, all from upstream
Metrolist, predating this gate: the public InnerTube client keys in
`innertube/.../YouTubeClient.kt` and `InnerTube.kt` (the same
WEB/ANDROID/IOS/etc keys every third-party YouTube client — yt-dlp,
NewPipe, Metrolist itself — ships baked into its source, extracted from
Google's own official apps) and `PoTokenWebView.kt`'s
`GOOGLE_API_KEY`/`REQUEST_KEY` pair (the public BotGuard PO-token
endpoint constants used identically by the community PO-token-generator
projects). None are account- or billing-linked secrets under this
project's control, and none are ours to rotate.
`security/gitleaks-baseline.json` freezes exactly these 16 findings by
`(commit, file, rule)` fingerprint; a new secret in a new commit still
fails the gate (verified with a synthetic high-entropy test key).

## Branch protection on `main` (applied in Task 3)

Task 1 fixed the exact policy so Task 3 could apply it without
re-deciding anything. Applied 2026-10-18 against commit `a5f1be48`
(check-run names read back via
`gh api repos/diamondspark/saraswati/commits/a5f1be48/check-runs --jq
'.check_runs[].name'`: `CodeQL`, `analyze`, `build-test` — only the
latter two, Task 3's own jobs, are required):

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

Verified via
`gh api repos/diamondspark/saraswati/branches/main/protection --jq
'{strict: .required_status_checks.strict, contexts:
.required_status_checks.contexts, force_pushes:
.allow_force_pushes.enabled, deletions: .allow_deletions.enabled}'`:

```json
{"contexts":["build-test","analyze"],"deletions":false,"force_pushes":false,"strict":true}
```

**Finding from verifying rejection.** The brief's verification step
(push a throwaway commit directly to `main`, expect rejection) instead
**succeeded**, logged by GitHub as `Bypassed rule violations for
refs/heads/main: - 2 of 2 required status checks are expected.` This is
`enforce_admins: false` working exactly as designed — deliberately
chosen in Task 1 so the sole owner can still push directly when
needed — but it means the protection does **not** block the owner's own
authenticated pushes, only unprivileged ones (ordinary PRs, non-admin
collaborators, or the owner's own token if `enforce_admins` were ever
flipped to `true`). The throwaway commit (`05ec7c67`, zero file
changes) landed on `main` as a result and could not be removed by this
session: restoring `main` to its prior tip requires a force-push /
history rewrite, which this session's own safety tooling blocks
outright as a destructive git operation, consistent with never force-
pushing `main` without a human's explicit say. **The owner needs to
either remove `05ec7c67` from `main` directly
(`git push origin e8480243:main --force`) or decide the empty, clearly-
labeled commit is harmless to leave.**

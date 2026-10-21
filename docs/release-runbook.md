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

`build-test` runs the app's unit tests and `:update-core:test` /
`:updater:testDebugUnitTest`, builds both unsigned release APKs (app and
Saraswati Updates, R8-minified), and gates each with `apk_gate.py`
against its own allowlist (`security/manifest-allowlist.json`,
`security/updater-allowlist.json`). A second job, `release-dry-run`, then
rehearses the release `sign` job on every push/PR with no secret
involved: it signs both APKs with `scripts/sign_release.sh` using a
throwaway keystore generated inside the job in the same shape as
`scripts/key-ceremony.sh` (default PKCS12 type, RSA-4096, separate
store- and key-password files), checks the signer with
`scripts/verify_apk_signer.sh` (the matching key must pass, a second
throwaway key's digest must be rejected), and re-runs both manifest
gates on the signed APKs. These are the same two scripts, PATH setup and
keystore shape the real `sign` job uses, so a bug in the signing
commands, the signer check, the manifest gates or the way a keystore is
generated shows up here first (verified: reverting the dry run to the
ceremony's original two-different-passwords shape makes it fail at
signing). What it cannot catch is anything specific to the real
`release` environment: wrong or stale secret *values* (such as the
`RELEASE_KEY_PASSWORD` described under "Status" below), the environment's
approval rules, or a keystore that doesn't match
`security/release-cert.sha256`. Those only surface on a real run.

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

**Mechanism: `.gitleaksignore`, not `--baseline-path`.** The first
version of this fix used `gitleaks detect --baseline-path
security/gitleaks-baseline.json`. A dedicated review
(`.superpowers/sdd/2026-10-17-saraswati-phase2-updates-pipeline/task-3-review.md`)
found and proved a critical hole: gitleaks excludes the file named by
`--baseline-path` from scanning in its entirety, in every commit (its
own source, `detect/detect.go`: `fragment.FilePath == d.baselinePath` is
skipped before any per-finding comparison runs). The reviewer
reproduced it live — appending a fabricated secret to that file and
committing it made the secret permanently invisible to the gate (exit
0). Since the repo has no required PR reviewers
(`required_pull_request_reviews: null`), nothing else would have caught
a future PR (malicious, careless, or a tampered upstream-sync commit)
doing the same thing for real.

Replaced with `.gitleaksignore` (repo root): a list of exact
`commit:file:rule-id:start-line` fingerprints. Verified this does *not*
have the same hole, rather than assuming — read gitleaks' source
(only `--baseline-path`/`--config` get the blanket file-skip;
`.gitleaksignore` entries are matched per-finding, after scanning, in
`AddFinding`) and reproduced the reviewer's exact attack shape against
it: a fabricated secret smuggled *inside* `.gitleaksignore`'s own
content was still caught (exit 1); a new secret added anywhere else was
still caught (exit 1). `.gitleaksignore` lists 32 fingerprints, not 16:
deleting `security/gitleaks-baseline.json` from the working tree
doesn't erase it from history, and the commit that had introduced it
(`a5f1be48`) embedded the same 16 secrets a second time as plaintext
JSON fields, so that commit's own fingerprints needed listing too.
Final state (`f275ada3`), verified against a clean mirror clone: `gitleaks
detect --source . --redact --report-format sarif --exit-code 1` →
`no leaks found`, exit 0; a freshly fabricated secret committed on top →
caught, exit 1.

**Residual risk, noted rather than silently accepted:** a single PR
that edits both a source file and `.gitleaksignore` could still pair a
new secret with a matching exemption — the same property every
content-addressed allowlist in this repo has (`egress-allowlist.json`,
`manifest-allowlist.json`, `workflow-allowlist.txt`) once there are no
required reviewers. Not unique to this mechanism or this fix; a
`check_workflows.py`-style script that pins `.gitleaksignore`'s exact
expected contents would close it if the owner wants that extra rung.

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

## Signing ceremony, release, and promotion (Task 6)

### Status: done — first signed release shipped

The ceremony has run: `security/release-cert.sha256` is committed
(landed with `c95fa1ac`) and the four `release`-environment secrets are
set. `:updater`'s release build embeds that digest as
`PINNED_CERT_SHA256`, and `sign` verifies every signed APK against it.
Release `v3902` (2026-10-18, `beta` channel) completed the full
`verify → build → sign → publish` pipeline successfully, confirming the
secrets are correct end to end.

**Known issue from the original run (fixed in the script, and the
live secret, since):** the script used to generate two *different*
random passwords for the store and the key. keytool's default keystore
type, PKCS12, has a single password for the whole store — keytool
printed "Different store and key passwords not supported for PKCS12
KeyStores. Ignoring user-specified -keypass value." and protected the
key with the store password. The uploaded `RELEASE_KEY_PASSWORD`
therefore didn't unlock the key, and `sign` failed with
`UnrecoverableKeyException` (`sign_release.sh` adds a "KS_PASS and
KEY_PASS differ" hint) until the owner re-set `RELEASE_KEY_PASSWORD` to
the **store password's** value — done, and confirmed by `v3902`'s
successful `sign` job. `scripts/key-ceremony.sh` now writes one
password to both files, so a future ceremony (key rotation) can't
repeat this.

The ceremony instructions below are kept as the reference for how the key
was made and for a future rotation.

**The owner needs to run the ceremony themselves, in their own
terminal, on a machine they trust — never an agent, never a shared or
ephemeral machine:**

```bash
cd <repo root>
bash scripts/key-ceremony.sh
```

It creates `~/saraswati-keys` (mode 700); generates one random
password and writes it to two files (store password, key password —
identical by design, since PKCS12 has only one) that are never printed,
logged, or placed on a command line; generates the RSA-4096
release key with `keytool -genkeypair` (alias `saraswati`, 10000-day
validity, `CN=Saraswati Family`); writes the certificate's SHA-256
digest to `security/release-cert.sha256` (public information — the
digest cannot be used to derive the private key); uploads the four
secrets to the `release` GitHub environment with `gh secret set
<NAME> --env release` — `RELEASE_KEYSTORE_B64`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`, each piped from a file or `base64 -w0`, never
echoed; and makes one encrypted backup with `gpg --symmetric
--cipher-algo AES256` (gpg prompts for a passphrase interactively — it
is never scripted or stored by this tool).

No agent runs this script and no agent reads the password files it
creates. This is the one step in the whole release pipeline that stays
entirely in the owner's hands.

**After running it:**

1. Copy the printed `release-key-backup.tar.gz.gpg` to **two separate
   offline media** (two different USB drives, not both in the same
   bag) and confirm both copies' `sha256sum` match the fingerprint the
   script printed.
2. Save the gpg passphrase in your password manager — not alongside
   either backup copy.
3. Verify: `gh secret list --env release` lists exactly
   `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; `cat
   security/release-cert.sha256` is 64 lowercase hex characters;
   `git status` shows no keystore or password file (only
   `security/release-cert.sha256` as a new tracked file).
4. Only once both backups are confirmed, securely delete the working
   directory: `shred -u ~/saraswati-keys/*; rmdir ~/saraswati-keys`.
5. Commit `security/release-cert.sha256`.

### Before every release

- **The festival table.** The Home banner and Saral's ribbon read their dates
  from `FestivalCalendar.all` (`app/src/main/kotlin/com/metrolist/music/saraswati/culture/FestivalCalendar.kt`).
  Lunar-calendar dates cannot be computed, so they are typed in from Drik
  Panchang, and the table currently ends on **2028-10-19** (Diwali 2028). Extend it
  (and the second, independently typed copy in `FestivalCalendarTest`) with the
  next years **before 2028-10-19**: after the last entry no festival shows and
  nothing else says why (the app is otherwise unaffected: it costs a banner, not
  playback).
  - It never blocks a release. From 2028-04-19 the unit test `the festival table is
    not within six months of running out` **skips** (it shows as skipped, with the
    extend-the-table message, and prints a `::warning::` line) in `saraswati-ci`
    and in the release `verify` job.
  - The alert is the weekly `festival-table` workflow
    (`.github/workflows/saraswati-festival-table.yml`, Mondays 05:23 UTC, also
    runnable by hand from Actions): it runs only that test in its strict mode,
    where it **fails** from 2028-04-19 and GitHub emails the owner. The strict
    mode is the Gradle property `-Psaraswati.strictFestivalTable=true` and is
    used nowhere else. To check by hand:
    `source scripts/dev-env.sh && sar_gradle :app:testGmsDebugUnitTest --tests '*FestivalCalendarTest' -Psaraswati.strictFestivalTable=true`.
  - Add a calendar reminder for 2028-04 as well; the workflow only helps while
    its notification emails are read.
- Put "festival table extended through <year>" in the release notes the first time
  this is done, so the next person can see when it was last looked at.

### How to release

1. In GitHub: **Actions → saraswati-release → Run workflow**, on
   `main`. Choose `channel` (`beta` or `stable` — almost always
   `beta`; `stable` is reached through promotion below, not a direct
   release), `urgent` (only for a fix that must skip promotion's
   48-hour wait), and `notes` (shown to users, ≤1000 chars — the
   `publish` job's manifest build fails if this is exceeded).
2. `verify` re-runs the full `ci` gate (including `release-dry-run`)
   against the dispatched ref; `build` assembles both unsigned APKs.
   Both run unattended.
3. `sign` stops and waits: **approve the `release` environment in the
   GitHub mobile app** (a push notification, or Actions → the run →
   Review deployments). This is the only point the signing key is ever
   touched — it is base64-decoded from secrets into the runner's
   `$RUNNER_TEMP` for the lifetime of this one job and deleted (via
   `shred -u`, falling back to `rm -f`) before the job ends either way.
4. `sign` zipaligns and signs both APKs with `scripts/sign_release.sh`
   (`apksigner`), then compares each signer's SHA-256 against the
   committed `security/release-cert.sha256` with
   `scripts/verify_apk_signer.sh` — **the job fails outright on any
   difference**, so a release can never ship signed with the wrong
   key — and runs `apk_gate.py check` on both signed APKs, each against
   its own allowlist.
5. `publish` creates a GitHub Release `v<versionCode>`, its tag pinned
   (`--target`) to the exact commit the run built from, with both signed
   APKs and their `.sha256` checksum files, builds the channel
   manifest with `scripts/make_manifest.py`, and pushes
   `<channel>.json` to the `gh-pages` branch.
6. Verify: `curl -sI https://diamondspark.github.io/saraswati/<channel>.json`
   returns 200 with a JSON content type (once Pages is enabled — Task
   8); both released APKs pass `apksigner verify --print-certs`
   against `security/release-cert.sha256`; and both pass
   `python3 -I security/apk_gate.py check`, each against its own
   allowlist — the Saraswati APK against
   `security/manifest-allowlist.json`, the Saraswati Updates APK against
   `security/updater-allowlist.json`.

### How to promote (beta → stable)

1. **Actions → saraswati-promote → Run workflow**, with
   `version_code` set to the beta release's `versionCode` (from its
   GitHub Release tag or `beta.json`).
2. **Approve the `release` environment**, the same gate as a release —
   promotion reaches the same protected environment even though it
   signs nothing.
3. The job refuses unless `beta.json`'s `publishedAt` is at least 48
   hours old, *unless* `beta.json.urgent` is `true` (Review Focus item
   6 of the phase 2 plan: a phone must never be silently pushed an
   untested beta onto stable without either time for problems to
   surface or an explicit urgency call). It also refuses if the given
   `version_code` doesn't match `beta.json`'s actual `versionCode` — a
   guard against promoting a different build than the one that was
   reviewed, in case a newer beta shipped in between. And it refuses if
   the current `stable.json` already has a higher `versionCode` (e.g. a
   newer build released directly to `stable`), so promotion can never
   move stable backwards; `publish` re-checks this against `gh-pages`
   right before pushing.
4. On success, `stable.json` on `gh-pages` becomes `beta.json` with
   `"channel"` rewritten to `"stable"`.

**The first promotion must happen before a phone is onboarded through
Saraswati Updates.** Until `stable.json` exists, a freshly installed Updates
app (it defaults to `stable`, and has no channel picker) shows "can't reach the
update server" and installs nothing. This is what happened on the first family
phone. After the first promotion every later release just replaces the file.

## Sign-in network hosts (keep LoginUrlPolicy and the network config in step)

Google ends a sign-in by redirecting through
`accounts.google.<country>/accounts/SetSID`, once for each country domain tied
to the account (one account's chain ran `.ca`, then `.co.in`, then
music.youtube.com). Two files must agree on those hosts:

- `LoginUrlPolicy` decides which pages the sign-in WebView may navigate to. It
  allows `accounts.google.<ccTLD>`, `.co.<cc>` and `.com.<cc>`.
- `app/src/main/res/xml/network_security_config.xml` (and its twin
  `security/egress-allowlist.json`) decides which hosts the TLS handshake will
  trust. It lists the same 185 `accounts.google.<ccTLD>` hosts, exact hosts only.

If the two drift apart, sign-in freezes after the password or the 2-step
approval: the navigation is allowed but the handshake is refused, and the page
just sits there. To refresh the list, take Google's country domains from
`https://www.google.com/supported_domains`, keep those whose
`accounts.<domain>` matches the `LoginUrlPolicy` pattern (this drops
`google.com`, already covered, and `google.cat`), and update both files.

To see which host a stuck sign-in is waiting on, install a debug build, forward
the WebView's debug socket
(`adb forward tcp:9333 localabstract:webview_devtools_remote_<app pid>`), and
listen for `Network.requestWillBeSent` redirects and `Network.loadingFailed`
events over the Chrome DevTools protocol.

## Developer verification (Task 13)

Full detail (what Google's pages actually say, the registration sheet, and
registration status) lives in [`docs/developer-verification.md`](developer-verification.md).
Summary for Phase 3 rollout:

1. Both package names (`dev.diamondspark.saraswati`,
   `dev.diamondspark.saraswati.updater`) must be registered under a free
   Limited Distribution Android Developer Console account — a human
   checkpoint, done by the owner signing in themselves, never by an agent.
2. Each family phone then needs its own **device authorization** before
   Saraswati can be installed there under enforcement: generate that
   phone's QR code or link from the Developer Console, open/scan it on the
   phone, and confirm the on-device consent prompt. This is a per-phone
   step, capped at 20 authorized devices total.
3. Fold this into the Phase 3 remote-setup flow, alongside the existing
   "Unknown sources"/Auto Blocker/battery steps, before the family member
   installs Saraswati Updates.
4. Track authorized-device count against the 20-device cap by hand in the
   rollout log in [`rollout-checklist.md`](rollout-checklist.md) (the family
   hub can't see Google's device authorizations), so there's headroom
   visibility before a phone upgrade or replacement needs a slot freed up.

### If the signing key is lost

Every installed Saraswati and Saraswati Updates pins
(`:updater`'s `BuildConfig.PINNED_CERT_SHA256`, sourced from
`security/release-cert.sha256`) the *current* release certificate's
SHA-256. If the private key and **both** encrypted backups are gone,
nothing can sign an update Android will accept as an upgrade of the
existing install — Android refuses to install an APK signed by a
different key over an existing package of the same name
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

Recovery needs a new key ceremony (`scripts/key-ceremony.sh` again,
after moving or deleting the old `security/release-cert.sha256` — the
script refuses to run over an existing non-empty one) and, on **every**
family phone:

1. Uninstall Saraswati. This loses local-only app state (the sign-in
   cookie in particular; music library data synced from YouTube Music
   is unaffected) — the user signs in again after reinstalling.
2. Uninstall Saraswati Updates.
3. Reinstall Saraswati Updates (via `adb install`, or however a fresh
   phone is normally set up — Phase 3 owns that flow), then use it to
   install the new Saraswati build signed by the new key.

This is exactly why there are two offline backups on separate media:
the uninstall/reinstall path above is the fallback for losing *all* of
them at once, not the expected outcome of an ordinary key ceremony.

### Key rotation (a planned key change, not a loss)

APK Signature Scheme v3 supports **key rotation**: a new signing key
can take over while phones that still only trust the old key keep
working, by having the new certificate carry a signed lineage back to
the old one. For Saraswati:

1. **Add the new key's certificate digest to `:updater` first.**
   `:updater`'s `PINNED_CERT_SHA256` (comma-separated — see its debug
   build type in `updater/build.gradle.kts` for the existing pattern of
   accepting more than one digest at once) must already include the
   digest the *next* release will be signed with, and that build of
   `:updater` must have reached every phone, **before** any release
   actually switches to signing with the new key. A phone running an
   `:updater` build that doesn't yet recognize the new digest would
   refuse the very release meant to deliver it.
2. Run a fresh key ceremony for the new key material (a new
   `~/saraswati-keys`, a new RSA-4096 pair) — but this is a lineage,
   not a fresh issue, so `security/release-cert.sha256` is not simply
   overwritten the normal first-ceremony way.
3. Build the rotation lineage with `apksigner rotate` (consult
   `apksigner rotate --help` for the exact flags on the apksigner
   version in use at the time): the old keystore and the new keystore
   both sign the lineage, producing a `lineage.bin` that `apksigner
   sign --lineage lineage.bin --rotation-min-sdk-version <n>` then
   needs alongside the new key for every subsequent release.
4. Release as normal only after confirming the `:updater` build
   carrying the new digest has actually propagated to every phone —
   ship the digest update first, confirm it, then switch signing keys;
   doing it in the other order is exactly the scenario `:updater`'s
   pinning exists to prevent.
5. Once the rotation is confirmed working on real phones, retire the
   old key's secrets from the `release` environment and its own
   encrypted backup.

## Phase 3 rollout

How a build gets onto a family phone, in order:

1. **Ship the build.** Release to `beta` ("How to release"), wait out the
   48 hours, then promote to `stable` ("How to promote"). The family phones
   follow Stable.
2. **Set up the family hub once** (owner only): `cd worker && npm ci` (once,
   it installs the pinned `wrangler`; do it before `wrangler login`), then
   `cd worker && npx wrangler deploy` from commit `8bf722b1` or later (it
   contains the hashed-token migration, approval expiry, body limits and
   front-Worker routing; if you squash-merge, replace it with the merge
   commit). The Worker's migration runner upgrades a hub that is already live
   in place, so there is no "fresh hub" rule. Never roll back: once the
   migration has run, an older Worker version cannot read the renamed columns,
   so redeploy forward. If the URL that `wrangler deploy` prints differs from
   the app's `HUB_BASE_URL`, stop. Once the build from step 1 is on your own
   phone, do the admin key in one sitting: generate it with `openssl rand
   -hex 32` in your own terminal only, run `cd worker && npx wrangler secret
   put ADMIN_KEY`, and paste it once into the app's Manage Family setup. Never
   put the key in chat or a file. If your phone is lost or stolen, run `npx
   wrangler secret put ADMIN_KEY` with a NEW value at once.
   Step by step in
   [`rollout-checklist.md`](rollout-checklist.md#before-the-first-phone).
3. **Set up each phone** with [`rollout-checklist.md`](rollout-checklist.md):
   one checklist per phone, covering the architecture check, developer
   verification, install, phone hygiene, pairing and approval, role (adult or
   grandparent), Android Auto, and the 30-day reminder to uninstall Metrolist.
   The family member follows the setup guide, live on a video call:
   <https://diamondspark.github.io/saraswati/setup-guide/> (live once a release
   run publishes it: the release workflow copies `docs/setup-guide` to
   `gh-pages` next to the channel manifest). Don't publish the setup guide
   (cut a release) until the app shows the 4-character approval code and
   tolerates the new approve reply; that is app work and it has to land first.

Known limits, also listed in the checklist:

- The hub caps owner actions at 20 a minute, so a refresh-happy Manage Family
  screen can show "try again in a minute".
- The free Cloudflare plan's daily request quota is a residual risk, and
  anyone can burn it, not just a misbehaving phone; revoking a phone does not
  help. The front Worker answers only requests with no Authorization header
  and unknown routes without waking the Durable Object; `/ping`,
  `/pairing/claim`, `/pairing/status` and any request carrying some Bearer
  still wake it, and every request costs a Worker request.
- A pairing link expires after 15 minutes and works once. A claimed phone
  can only be approved within 1 hour.

Developer verification is a per-phone step (see "Developer verification (Task
13)" above); the full procedure is in
[`developer-verification.md`](developer-verification.md).

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
different method being picked by mistake: branch protection (applied
above, Task 3) covers required status checks and force-push/deletion
rules, but does not control merge methods — that's a separate
repository-level setting, and it is still at its GitHub default (all
three merge methods enabled) as of this writing. **A human needs to set
this directly in GitHub: Settings → General → Pull Requests → uncheck
"Allow squash merging" and "Allow rebase merging," leaving only "Allow
merge commits" checked** (or, narrower, leave all three enabled and just
always pick "Create a merge commit" by hand specifically for
`sync/upstream-*` PRs). This is not something this bot, or an agent
working on this repo, can or should set via `gh api` on its own
initiative — it's a repo-wide policy change the owner should make
deliberately.

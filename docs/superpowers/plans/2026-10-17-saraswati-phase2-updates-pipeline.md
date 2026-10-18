# Saraswati Phase 2: Updates and Release Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Everything needed to ship Saraswati to family phones safely without the user's computer: locked-down GitHub, CI gates, a human-approved signed release, a tiny separate "Saraswati Updates" app that is the only installer, an "Update ready" banner, a sync bot for upstream fixes, measured startup performance, and developer-verification registration.

**Architecture:** Two Android apps signed with one release key. `:update-core` (pure Kotlin/JVM, fully unit-tested) holds the manifest schema, version decision, APK verification and download policy, and is shared by both apps. `:updater` is the only app that can install. GitHub Actions builds and gates everything; only a protected `release` environment (one-tap approval in the GitHub mobile app) can see the signing key. GitHub Pages hosts `stable.json` and `beta.json`; GitHub Releases host the APKs.

**Tech Stack:** Kotlin, Android `PackageInstaller`, kotlinx.serialization (already audited in the main app), GitHub Actions + `gh`, Python 3 stdlib (`unittest`) for gates, apksigner/zipalign, CodeQL, gitleaks, MobSF (Docker), androidx Macrobenchmark + Baseline Profile, JUnit4.

**Spec:** `/home/mohit/.claude/plans/i-am-creating-my-serene-engelbart.md`. Phase 1 plan: `2026-10-17-saraswati-phase1-fork-harden.md` (must be merged first).

## Global Constraints

- **Packages and names:** `dev.diamondspark.saraswati` ("Saraswati"), `dev.diamondspark.saraswati.updater` ("Saraswati Updates"). Modules: `:update-core` (Kotlin JVM, no Android dependency), `:updater` (Android app), later `:baselineprofile`.
- **URLs:** APKs only from `https://github.com/diamondspark/saraswati/releases/download/<tag>/<file>`; manifests only from `https://diamondspark.github.io/saraswati/stable.json` and `.../beta.json`. A GitHub download may redirect only to hosts ending in `githubusercontent.com`.
- **Manifest schema 1 (exact):** `{"schema":1,"channel":"stable|beta","versionCode":<int>,"versionName":"<string>","apk":{"url":"…","sha256":"<64 lowercase hex>","size":<int ≤ 104857600>},"urgent":<bool>,"notes":"<≤1000 chars>","publishedAt":"<ISO-8601 UTC>","updater":null|{"versionCode":<int>,"url":"…","sha256":"…","size":<int>}}`. The manifest is **not trusted for identity**: the signer comes from the pinned certificate digest, never from the manifest.
- **Signing:** RSA 4096, key alias `saraswati`, APK Signature Scheme v2+v3 via `apksigner` (zipaligned first). The release certificate SHA-256 is committed in `security/release-cert.sha256` (public information) and pinned into `:updater`. Debug builds of `:updater` additionally accept the local `~/.android/debug.keystore` certificate; release builds accept only the committed digest.
- **Secrets:** only in the `release` GitHub environment (`RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`), never at repository level, never printed, never on the dev machine after the ceremony. No agent ever sees a password.
- **Workflows:** every `uses:` is pinned to a 40-character commit SHA with a version comment; top-level `permissions: contents: read` (jobs raise it only where needed); no `pull_request_target`, no `workflow_run`; workflow files must be in `security/workflow-allowlist.txt`; secrets other than `GITHUB_TOKEN` appear only in `saraswati-release.yml` and `saraswati-promote.yml`.
- **`:updater` permissions:** `INTERNET`, `REQUEST_INSTALL_PACKAGES` only; `<queries>` for `dev.diamondspark.saraswati` only; `allowBackup=false`; HTTPS only; network config trusts nothing except `github.com`, `githubusercontent.com`, `diamondspark.github.io`; no WebView, no JavaScript, no server socket; R8 on.
- **Install-permission rule:** after any install attempt (success, failure or cancel) the Updater keeps asking the user to switch "Install unknown apps" off until `PackageManager.canRequestPackageInstalls()` is `false`.
- **User-facing copy:** plain words, big buttons. Samsung phones (`Build.MANUFACTURER` equals "samsung", case-insensitive) see the extra Auto Blocker steps.
- **Everything from Phase 1's Global Constraints still applies** (identity, SDK/JDK paths, `// SARASWATI:` markers, commit trailer, memory limits, no secrets in output). Work on branch `phase2-pipeline`.
- **Human checkpoints (the agent stops and asks):** GitHub 2FA confirmation (Task 1), key ceremony (Task 6), approving the release in the GitHub app (Tasks 6 and 8), reinstall and sign-in on the Pixel (Task 8), Sentry project and DSN (Task 12), Android Developer Console registration (Task 13).

## Review Focus

1. **Downgrade, replay and redirect attacks on the update path:** a manifest pointing at an older signed APK, a URL outside the repo, a redirect to another host, an APK whose hash matches but whose signer differs, a package-name mismatch. Owners: Task 4 (core tests), Task 5.
2. **The install permission is left ON** after success, failure, cancel or a crash mid-flow. Owner: Task 5 (state machine and on-device check in Task 8).
3. **A malicious upstream merge or PR changes workflows or starts using secrets.** Owners: Task 2 (policy checker), Task 9 (sync bot flags any `.github/` change).
4. **A release is signed with the wrong key, or an unsigned APK is published.** Owner: Task 6 (post-sign check against `security/release-cert.sha256` before publishing).
5. **Offline phone, GitHub down, rate limited, Pages not yet created:** the Updater and the banner fail quietly with a retry, and never crash or block playback. Owners: Tasks 5 and 7.
6. **Channel ordering:** a phone on a newer Beta build must not be "updated" to an older Stable one; it should be told it is already ahead. Owner: Task 4 (`UpdateDecider`).

---

### Task 1: Repository security: Actions policy, branch protection, release environment

**Files:**
- Create: `docs/release-runbook.md` (started here, finished in Task 6)

**Interfaces:**
- Produces: GitHub Actions enabled with an allowlist of actions; default token read-only; branch protection on `main`; environment `release` requiring the owner's approval and limited to branch `main`.

- [ ] **Step 1: HUMAN CHECKPOINT.** Ask the user to confirm that GitHub sign-in uses a passkey or security key (or at least an authenticator app), that recovery codes are stored offline, and that no old personal access tokens are in use. Do not continue until they confirm.
- [ ] **Step 2: Enable Actions with a restricted policy.** `gh api -X PUT repos/diamondspark/saraswati/actions/permissions --input -` with `{"enabled":true,"allowed_actions":"selected"}`; then `.../actions/permissions/selected-actions` with `{"github_owned_allowed":true,"verified_allowed":false,"patterns_allowed":["gradle/actions/*"]}`; then `.../actions/permissions/workflow` with `{"default_workflow_permissions":"read","can_approve_pull_request_reviews":false}`. Verify by reading each endpoint back with `gh api`.
- [ ] **Step 3: Create the release environment.** Get the owner's numeric id with `gh api user --jq .id`. `gh api -X PUT repos/diamondspark/saraswati/environments/release --input -` with `{"prevent_self_review":false,"reviewers":[{"type":"User","id":<id>}],"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}`, then `gh api -X POST repos/diamondspark/saraswati/environments/release/deployment-branch-policies -f name=main -f type=branch`. Verify: `gh api repos/diamondspark/saraswati/environments/release --jq '[.protection_rules[].type, (.deployment_branch_policy|tostring)]'` lists `required_reviewers` and the custom branch policy.
- [ ] **Step 4: Branch protection (applied in Task 3 once check names exist).** Write the exact `gh api -X PUT repos/diamondspark/saraswati/branches/main/protection` JSON into `docs/release-runbook.md` now: required status checks `build-test` and `analyze` (strict), `enforce_admins:false`, no force pushes, no deletions, no required reviewers (the release approval is the human gate; the owner works through pull requests).
- [ ] **Step 5: Commit** the runbook stub. `docs: release runbook skeleton and repository policy`.

### Task 2: Workflow policy checker

**Files:**
- Create: `security/check_workflows.py`, `security/test_check_workflows.py`, `security/workflow-allowlist.txt`

**Interfaces:**
- Produces: `def check_workflows(files: dict[str, str], allowlist: list[str]) -> list[str]` (`files` maps file name to text; empty list = pass). Exact violation strings: `unexpected workflow file: <name>`, `unpinned action: <name>: <uses-value>`, `docker action not allowed: <name>: <uses-value>`, `missing top-level permissions: <name>`, `pull_request_target is not allowed: <name>`, `workflow_run is not allowed: <name>`, `secret used outside release workflows: <name>: <SECRET_NAME>`. CLI `python3 -I security/check_workflows.py` reads `.github/workflows/*.yml` and `security/workflow-allowlist.txt` and exits 1 on any violation. Stdlib only, regex based (no YAML parser).
- Allowlist content: `saraswati-ci.yml`, `codeql.yml`, `saraswati-release.yml`, `saraswati-promote.yml`, `saraswati-upstream-sync.yml`.

- [ ] **Step 1: Write failing tests** (inline fixtures): `test_clean_workflow_passes`, `test_flags_unpinned_action_tag` (`actions/checkout@v4`), `test_accepts_sha_pinned_action_with_comment`, `test_accepts_local_action`, `test_flags_docker_action`, `test_flags_missing_permissions`, `test_flags_pull_request_target`, `test_flags_workflow_run`, `test_flags_secret_in_ci_workflow` (`secrets.SOMETHING`), `test_allows_github_token_everywhere`, `test_allows_secrets_in_release_workflow`, `test_flags_unexpected_file`. Run the suite. Expected: FAIL.
- [ ] **Step 2: Implement** per the interface. Run. Expected: PASS.
- [ ] **Step 3: Commit.** `feat(security): workflow policy checker`.

### Task 3: CI workflow, CodeQL, secret scanning, Dependabot

**Files:**
- Create: `.github/workflows/saraswati-ci.yml`, `.github/workflows/codeql.yml`, `.github/dependabot.yml`, `scripts/install-gitleaks.sh`
- Modify: `docs/release-runbook.md`

**Interfaces:**
- Produces: workflow `ci` (also `workflow_call` and `workflow_dispatch`) with job id/name `build-test`: checkout (`fetch-depth: 0`, `persist-credentials: false`) → `gradle/actions/wrapper-validation` → `setup-java` (Temurin 21) → `setup-gradle` → Python suites → `check_workflows.py` → `./gradlew --dependency-verification strict :app:testGmsDebugUnitTest :app:assembleGmsRelease` (Tasks 4 and 5 add `:update-core:test` and `:updater:assembleRelease` to this command in the same commits that create those modules) → `apk_gate.py check` on the unsigned release APK → gitleaks on the full history → upload unsigned APKs and reports. Workflow `codeql` with job `analyze`.

- [ ] **Step 1: Resolve action pins.** For each action used, resolve the newest stable tag to a commit with `gh api repos/<owner>/<repo>/git/ref/tags/<tag> --jq .object` (dereference annotated tags through `.../git/tags/<sha>`), and write `uses: owner/repo@<40-hex> # <tag>`. Verify with `python3 -I security/check_workflows.py` (Task 2). Expected: exit 0.
- [ ] **Step 2: Write `saraswati-ci.yml`** per the interface; `timeout-minutes: 45`; `concurrency` cancels superseded runs.
- [ ] **Step 3: Write `scripts/install-gitleaks.sh`:** downloads a pinned gitleaks release for linux x64, verifies its SHA-256 from the release's published checksums (the expected hash is written into the script, not fetched), and installs it into `$RUNNER_TEMP`.
- [ ] **Step 4: Write `codeql.yml`** (language `java-kotlin`; use `build-mode: manual` with `./gradlew :app:assembleGmsDebug -x test`, and fall back to `none` only if the SARIF shows no `.kt` files). Triggers: push to `main`, pull requests, weekly schedule.
- [ ] **Step 5: Write `.github/dependabot.yml`:** `gradle` and `github-actions` ecosystems, weekly.
- [ ] **Step 6: First green run.** Push `phase2-pipeline`, open a PR to `main`, and watch: `gh pr checks --watch`. Fix failures (typical: the Android platform or build-tools not preinstalled on the runner; the AGP downloads them when licenses are present, otherwise add an explicit `sdkmanager` step). Expected: `build-test` and `analyze` succeed.
- [ ] **Step 7: Apply branch protection** (Task 1, Step 4) using the real check names from `gh api repos/diamondspark/saraswati/commits/<sha>/check-runs --jq '.check_runs[].name'`. Verify by trying `git push origin main` from a throwaway commit: expected rejection.
- [ ] **Step 8: Commit.** `ci: build, test, gates, CodeQL, gitleaks, Dependabot`.

### Task 4: Update core library (`:update-core`)

**Files:**
- Create: `update-core/build.gradle.kts` (Kotlin JVM, `kotlinx-serialization-json`, JUnit4), `update-core/src/main/kotlin/dev/diamondspark/saraswati/updatecore/*.kt`, `update-core/src/test/kotlin/.../*Test.kt`, `security/fixtures/manifest_stable.json` (golden)
- Modify: `settings.gradle.kts` (`include(":update-core")`), `gradle/verification-metadata.xml` (regenerate and review the new entries)

**Interfaces:**
- Produces (package `dev.diamondspark.saraswati.updatecore`):
  - `data class ApkAsset(val url: String, val sha256: String, val size: Long)`
  - `data class UpdateManifest(val schema: Int, val channel: String, val versionCode: Long, val versionName: String, val apk: ApkAsset, val urgent: Boolean, val notes: String, val publishedAt: String, val updater: UpdaterAsset?)`, `data class UpdaterAsset(val versionCode: Long, val apk: ApkAsset)`
  - `object ManifestParser { fun parse(json: String): Result<UpdateManifest> }`: strict, failures carry a short reason.
  - `sealed interface Decision { object UpToDate; data class AlreadyAhead(val installed: Long); data class UpdateAvailable(val manifest: UpdateManifest); data class Rejected(val reason: String) }` and `object UpdateDecider { fun decide(installedVersionCode: Long?, manifest: UpdateManifest): Decision }`
  - `data class ArchiveInfo(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)`, `fun interface ArchiveInspector { fun inspect(file: java.io.File): ArchiveInfo? }`
  - `sealed interface VerifyResult { object Ok; data class Failed(val reason: String) }` and `object ApkVerifier { fun verify(file: java.io.File, asset: ApkAsset, expectedPackage: String, expectedVersionCode: Long, pinnedSignerSha256: Set<String>, inspector: ArchiveInspector): VerifyResult }`
  - `object DownloadPolicy { const val MAX_APK_BYTES = 104_857_600L; const val MAX_REDIRECTS = 3; fun isAllowedStartUrl(url: String): Boolean; fun isAllowedRedirect(from: String, to: String): Boolean }`
  - Constants: `RELEASE_URL_PREFIX = "https://github.com/diamondspark/saraswati/releases/download/"`, `MANIFEST_BASE_URL = "https://diamondspark.github.io/saraswati/"`.

- [ ] **Step 1: Write failing tests.** `ManifestParserTest`: `parses the golden manifest`, `rejects schema 2`, `rejects http url`, `rejects url outside the release prefix`, `rejects a sha256 that is not 64 lowercase hex`, `rejects size above 100 MiB`, `rejects unknown channel`, `rejects notes above 1000 chars`, `ignores unknown extra fields`. `UpdateDeciderTest`: `not installed offers the update`, `equal version is up to date`, `lower manifest version reports already ahead and never downgrades`, `higher version offers the update`. `ApkVerifierTest` (fake `ArchiveInspector`): `sha256 mismatch fails`, `wrong package fails`, `unpinned signer fails`, `version code mismatch fails`, `unreadable archive fails`, `all matching passes`. `DownloadPolicyTest`: `allows the release prefix`, `rejects http`, `rejects another github repo`, `allows a redirect to objects.githubusercontent.com`, `allows a redirect to release-assets.githubusercontent.com`, `rejects a redirect to evil.example`, `rejects a lookalike host such as githubusercontent.com.evil.example`. Run `sar_gradle :update-core:test`. Expected: FAIL (compilation).
- [ ] **Step 2: Implement** the types (JSON with `kotlinx.serialization`, `ignoreUnknownKeys = true`, no lenient parsing; sha256 streamed with `MessageDigest`). Run. Expected: all PASS.
- [ ] **Step 3: Regenerate dependency verification metadata** for the new module, review the diff, commit it with the module.
- [ ] **Step 4: Commit.** `feat: update-core with strict manifest, decision, APK and download rules`.

### Task 5: The "Saraswati Updates" app (`:updater`)

**Files:**
- Create: `updater/build.gradle.kts`, `updater/proguard-rules.pro`, `updater/src/main/AndroidManifest.xml`, `updater/src/main/res/xml/network_security_config.xml`, `updater/src/main/res/values/strings.xml`, adaptive launcher icon resources (placeholder marigold arrow), and Kotlin under `updater/src/main/kotlin/dev/diamondspark/saraswati/updater/`: `UpdateActivity.kt`, `UpdateFlow.kt`, `HttpFetcher.kt`, `AndroidArchiveInspector.kt`, `InstallSession.kt`, `InstallResultReceiver.kt`; tests `updater/src/test/kotlin/.../UpdateFlowTest.kt`
- Modify: `settings.gradle.kts` (`include(":updater")`), `security/manifest-allowlist.json` is not touched (separate APK; add `security/updater-allowlist.json` with `package`, `permissions: [INTERNET, REQUEST_INSTALL_PACKAGES]`, `exported_components: [activity:dev.diamondspark.saraswati.updater.UpdateActivity]`)

**Interfaces:**
- Consumes: everything in Task 4.
- Produces:
  - `applicationId "dev.diamondspark.saraswati.updater"`, `namespace` the same, minSdk 26, targetSdk 36, compileSdk 37; `BuildConfig.PINNED_CERT_SHA256: String` (comma separated; release = contents of `security/release-cert.sha256`; debug also appends the SHA-256 of the certificate in `~/.android/debug.keystore`, computed in the Gradle script with `java.security.KeyStore`); versionCode from `scripts/version.py`.
  - `sealed interface UiState { Checking; UpToDate(installed: Long); AlreadyAhead(installed: Long); UpdateReady(manifest: UpdateManifest); Downloading(percent: Int); NeedsInstallPermission(manifest: UpdateManifest); ReadyToInstall(manifest: UpdateManifest); Installing; TurnPermissionOff(version: String?); Done(version: String); Error(message: String, canRetry: Boolean) }`
  - `sealed interface Event { ManifestLoaded(manifest: UpdateManifest, installed: Long?); ManifestFailed(reason: String); DownloadProgress(percent: Int); DownloadFinished(verified: VerifyResult); UserTappedUpdate; PermissionChecked(canInstall: Boolean); InstallStatus(code: Int); Retry }` and `fun reduce(state: UiState, event: Event, canInstallNow: Boolean): UiState` (pure).
  - `UpdateActivity` exported, `LAUNCHER` and action `dev.diamondspark.saraswati.updater.CHECK`; `InstallResultReceiver` not exported; the commit `PendingIntent` is explicit, mutable (required for installer status extras).
  - Install steps in order: manifest fetch (channel from preferences, default `stable`; `?channel=` extra honored only for `beta`/`stable`) → `UpdateDecider` → download to `filesDir/updates/<versionCode>.apk` with `DownloadPolicy` (no automatic redirects; follow at most 3 manually, re-checking each host) → `ApkVerifier` (via `AndroidArchiveInspector` using `getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)`) → permission check → `PackageInstaller` session (`MODE_FULL_INSTALL`, user action required) → status handling → delete the APK. The same machinery updates `dev.diamondspark.saraswati.updater` itself when the manifest's `updater` section is newer.

- [ ] **Step 1: Write failing reducer tests** in `UpdateFlowTest`: `manifest newer than installed becomes UpdateReady`, `manifest equal becomes UpToDate`, `manifest older becomes AlreadyAhead`, `verification failure becomes a non-retryable Error and never reaches Installing`, `missing install permission becomes NeedsInstallPermission`, `granted permission becomes ReadyToInstall`, `install success while the permission is still on becomes TurnPermissionOff`, `install cancel while the permission is still on becomes TurnPermissionOff`, `install failure while the permission is on becomes TurnPermissionOff`, `TurnPermissionOff stays until the permission is off`, `permission off after success becomes Done`, `network failure becomes a retryable Error`. Run `sar_gradle :updater:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 2: Implement `reduce`** and the types. Run. Expected: PASS.
- [ ] **Step 3: Implement the Android glue** (activity with large buttons and short copy, fetcher on a background executor with 10 s connect and 30 s read timeouts, archive inspector, install session, result receiver). Copy to ship in `strings.xml`: "Update ready", "Allow installing (one time)", "Install", "All done. Now switch the permission off.", "You already have a newer test version.", plus a Samsung block: "Samsung phones: switch Auto Blocker off first (Settings → Security and privacy → Auto Blocker), and back on after."
- [ ] **Step 4: Network config and manifest.** NSC: `<base-config cleartextTrafficPermitted="false"><trust-anchors/></base-config>` plus `domain-config` entries (system trust) for the three allowed domains. Manifest exactly as in Global Constraints.
- [ ] **Step 5: Gate the updater APK.** Extend `security/apk_gate.py` only if needed; otherwise run `python3 -I security/apk_gate.py check --apk updater/build/outputs/apk/release/updater-release-unsigned.apk --allowlist security/updater-allowlist.json`. Expected: exit 0.
- [ ] **Step 6: Install the debug build on the Pixel** and open it. Expected (no release exists yet): it shows a calm retryable error ("Can't reach the update server"), no crash; with Wi-Fi off the same. Capture a screenshot with `npx -y rea-agents@6.5.0 capture-adb-screen`.
- [ ] **Step 7: Commit.** `feat: Saraswati Updates app, the only installer`.

### Task 6: Signing ceremony, release workflow, manifests and Pages

**Files:**
- Create: `scripts/key-ceremony.sh`, `scripts/make_manifest.py`, `scripts/test_make_manifest.py`, `security/release-cert.sha256`, `.github/workflows/saraswati-release.yml`, `.github/workflows/saraswati-promote.yml`
- Modify: `docs/release-runbook.md`, `security/fixtures/manifest_stable.json` (kept in sync with the builder)

**Interfaces:**
- Produces:
  - `scripts/make_manifest.py`: `def build_manifest(*, channel: str, version_code: int, version_name: str, apk_url: str, apk_sha256: str, apk_size: int, urgent: bool, notes: str, published_at: str, updater: dict | None = None) -> dict` (raises `ValueError` on any schema violation, including notes over 1000 chars); CLI writes the JSON to a path.
  - `saraswati-release.yml` (`workflow_dispatch` inputs `channel` [beta|stable, default beta], `urgent`, `notes`): job `verify` (calls the `ci` workflow), job `build` (unsigned APKs), job `sign` with `environment: release` (waits for the owner's approval; writes the keystore to `$RUNNER_TEMP`, `zipalign -p 4`, `apksigner sign`, `apksigner verify --print-certs`, compares the signer SHA-256 with `security/release-cert.sha256` and **fails on any difference**, runs `apk_gate.py check` on the signed app APK, deletes the keystore), job `publish` (`contents: write`; `gh release create v<versionCode>` with both APKs and `.sha256` files, builds the manifest with `make_manifest.py`, pushes `<channel>.json` to branch `gh-pages`).
  - `saraswati-promote.yml` (`workflow_dispatch` input `version_code`, `environment: release`): refuses unless the beta release is at least 48 hours old (skipped when its manifest says `urgent`), then rewrites `stable.json` from `beta.json` with `channel: "stable"`.
  - `scripts/key-ceremony.sh`: run by the user in their own terminal. It creates `~/saraswati-keys` (mode 700), generates two random passwords into files without printing them, runs `keytool -genkeypair -keyalg RSA -keysize 4096 -validity 10000 -alias saraswati -dname "CN=Saraswati Family"`, writes the certificate SHA-256 to `security/release-cert.sha256`, uploads the four secrets with `gh secret set <NAME> --env release` (value from a file or `base64 -w0`, never echoed), then creates an encrypted backup with `gpg --symmetric --cipher-algo AES256` (gpg prompts the user for the passphrase) and prints only fingerprints and file paths.

- [ ] **Step 1: Write failing tests** `scripts/test_make_manifest.py`: `output equals the golden file for fixed inputs`, `rejects an unknown channel`, `rejects a url outside the release prefix`, `rejects notes above 1000 chars`, `rejects a sha256 that is not 64 lowercase hex`. Run the suite. Expected: FAIL. Implement. Expected: PASS.
- [ ] **Step 2: Write the three workflow/script files.** Run `python3 -I security/check_workflows.py`. Expected: exit 0 (secrets appear only in the two release workflows).
- [ ] **Step 3: HUMAN CHECKPOINT: key ceremony.** Tell the user to run `bash scripts/key-ceremony.sh` themselves and to keep **two copies of the encrypted backup on separate offline media** and the passphrase in their password manager. The agent must not read the password files. Afterwards verify: `gh secret list --env release` lists exactly the four names; `security/release-cert.sha256` is 64 hex characters; `git status` shows no keystore or password file. Ask the user to confirm both backups exist, then to securely delete `~/saraswati-keys` (`shred -u` each file, `rmdir`).
- [ ] **Step 4: Finish `docs/release-runbook.md`:** how to release (dispatch, approve in the GitHub app), how to promote, what to do if the key is lost (every phone must uninstall and reinstall), and key rotation (v3 lineage with `apksigner rotate`, and add the new digest to `:updater` first).
- [ ] **Step 5: Commit** the workflows, scripts, tests and certificate digest. `ci: signed release and promotion workflows, manifest builder`.

### Task 7: "Update ready" banner and retiring Metrolist's updater

**Files:**
- Create: `saraswati/update/UpdateChecker.kt`, `saraswati/update/UpdateBanner.kt`, `app/src/test/kotlin/com/metrolist/music/saraswati/update/UpdateCheckerTest.kt`
- Modify: `build.gradle.kts` of `:app` (`implementation(project(":update-core"))`), `ui/screens/HomeScreen.kt` (one hook that shows the banner above the first shelf), `app/src/gms/AndroidManifest.xml` (`<queries><package android:name="dev.diamondspark.saraswati.updater"/></queries>`), `app/src/main/res/xml/network_security_config.xml` and `security/egress-allowlist.json` (add `diamondspark.github.io`), `ui/screens/settings/AboutScreen.kt` (seven taps on the version row toggle Beta with a toast)

**Interfaces:**
- Consumes: `ManifestParser`, `UpdateDecider`, `Decision` from Task 4; `BuildConfig.VERSION_CODE`.
- Produces: `class UpdateChecker(private val http: HttpFetcher, private val clock: () -> Long)` with `suspend fun check(installedVersionCode: Long, channel: String, lastCheckMillis: Long): UpdateStatus`; `sealed interface UpdateStatus { None; Ready(versionName: String, urgent: Boolean); Throttled; Failed }`; `fun interface HttpFetcher { suspend fun get(url: String): String? }`. Throttle: at most one network check per 12 hours, only on foreground. Banner copy: "Update ready" or, when `urgent`, "Fixes music playback" in the error color. Tapping starts `dev.diamondspark.saraswati.updater/.UpdateActivity` with action `dev.diamondspark.saraswati.updater.CHECK`; if the Updater is not installed it shows a short dialog pointing to the setup page.

- [ ] **Step 1: Write failing tests** (`ktor-client-mock` is already a test dependency): `newer manifest returns Ready`, `urgent manifest returns Ready(urgent = true)`, `same version returns None`, `older manifest returns None`, `second check inside 12 hours returns Throttled without a request`, `network failure returns Failed and never throws`, `malformed manifest returns Failed`. Run. Expected: FAIL.
- [ ] **Step 2: Implement** `UpdateChecker` and the banner; hook it into `HomeScreen` with a `// SARASWATI:` marker.
- [ ] **Step 3: Run the Python and JVM suites** including `security/test_egress_policy.py`. Expected: PASS.
- [ ] **Step 4: Commit.** `feat: Update ready banner backed by update-core`.

### Task 8: First release and the end-to-end update test on the Pixel

**Files:**
- Create: `docs/update-e2e.md`

- [ ] **Step 1: Merge.** Merge `phase2-pipeline` to `main` through its pull request after CI is green.
- [ ] **Step 2: First beta release.** Dispatch `saraswati-release.yml` (`channel=beta`). **HUMAN CHECKPOINT:** the user approves the `release` environment in the GitHub mobile app. Expected: the run finishes, a GitHub Release `v<versionCode>` exists with two signed APKs, and `beta.json` is on `gh-pages`.
- [ ] **Step 3: Enable Pages** (first time only): `gh api -X POST repos/diamondspark/saraswati/pages -f 'source[branch]=gh-pages' -f 'source[path]=/'`. Verify `curl -sI https://diamondspark.github.io/saraswati/beta.json` returns 200 with `content-type` JSON.
- [ ] **Step 4: Verify the artifacts locally.** Download both APKs, run `apksigner verify --print-certs` (signer SHA-256 equals `security/release-cert.sha256`) and `python3 -I security/apk_gate.py check` on the Saraswati APK. Expected: both pass.
- [ ] **Step 5: HUMAN CHECKPOINT: clean install on the Pixel.** Uninstall the temporary debug-key builds of Saraswati (this loses the sign-in; the user signs in again). Install the signed **Updater** with adb, then use the Updater to install Saraswati (set the Updater's channel to beta). This exercises the real routine: allow, install, switch the permission off. Record how long it takes.
- [ ] **Step 6: Second release.** Make a trivial visible change (the About text), release it to beta, approve, and on the Pixel confirm the banner appears, the Updater offers it, and `adb shell dumpsys package dev.diamondspark.saraswati | grep versionName` shows the new version afterwards.
- [ ] **Step 7: Check the permission rule.** After the update, `adb shell appops get dev.diamondspark.saraswati.updater REQUEST_INSTALL_PACKAGES` must show `deny` or `ignore` once the user flipped the switch off; before that the Updater screen must still say "switch the permission off". Test the cancel path too (cancel the system install dialog; the Updater must still ask).
- [ ] **Step 8: Independence test.** `adb shell pm clear dev.diamondspark.saraswati`, then run the Updater check. Expected: it works without Saraswati running (this is why a crashing Saraswati can still be repaired).
- [ ] **Step 9: Negative test.** Temporarily publish a `beta.json` that points at a debug-signed APK (hash correct, signer wrong). Expected: the Updater refuses with a clear message and never opens the install dialog. Restore `beta.json`.
- [ ] **Step 10: Write `docs/update-e2e.md`** with timings, screenshots and results; commit `docs: end-to-end update test results`.

### Task 9: Upstream sync bot

**Files:**
- Create: `.github/workflows/saraswati-upstream-sync.yml`, `scripts/sync_upstream.sh`, `scripts/sync_report.py`, `scripts/test_sync_report.py`

**Interfaces:**
- Produces:
  - `scripts/sync_report.py`: `def summarize_commits(git_log: str) -> list[str]` (subject lines, newest first), `def changed_hosts(old_sources: dict[str, str], new_sources: dict[str, str]) -> dict[str, list[str]]` (`{"added": [...], "removed": [...]}` of URL hosts found in source text), `def render_pr_body(commits: list[str], diffstat: str, toml_changes: list[str], host_changes: dict[str, list[str]], workflow_changes: list[str], gate_output: str) -> str` containing the checklist `- [ ] Security review of the diff done`, `- [ ] Dependency changes reviewed`, `- [ ] Permission and host changes approved`, and a prominent warning block when `workflow_changes` is non-empty.
  - Workflow (daily cron and `workflow_dispatch`, `permissions: contents: write, pull-requests: write, actions: write`, **no secrets**): fetch `https://github.com/MetrolistGroup/Metrolist.git` into `upstream-main`; if there are no new commits, exit; branch `sync/upstream-<YYYYMMDD>`; `git merge --no-ff upstream-main`; **automatic conflict rules**: `.github/workflows/**` always keeps ours (delete upstream-only files), nothing else is auto-resolved; any other conflict opens an issue listing the files and stops; a clean merge is pushed, a PR is opened with `render_pr_body`, label `needs-security-review`, and the `ci` workflow is dispatched on the branch (pull requests created with the default token do not trigger workflows themselves).
  - The bot never merges, never touches `gradle/verification-metadata.xml` (a changed dependency must fail strict verification so a person reviews it) and never touches releases.

- [ ] **Step 1: Write failing tests** for `sync_report.py` with fixtures: `summarize_commits parses a git log`, `changed_hosts reports added and removed hosts`, `render_pr_body includes the three checklist items`, `render_pr_body warns when workflow files changed`, `render_pr_body omits the warning when none changed`. Run. Expected: FAIL. Implement. Expected: PASS.
- [ ] **Step 2: Write `sync_upstream.sh` and the workflow;** run `python3 -I security/check_workflows.py`. Expected: exit 0.
- [ ] **Step 3: Dry run.** Dispatch the workflow against the current `main`. Expected: "no new upstream commits" (or, if upstream moved, a PR with the report and a green or deliberately red CI). Review the PR body for readability.
- [ ] **Step 4: Conflict path test.** Create a throwaway local branch change that conflicts with a known upstream file in a scratch clone and confirm the script stops with an issue-style message rather than a half-merged push.
- [ ] **Step 5: Commit.** `ci: upstream sync bot with security report`.

### Task 10: MobSF APK scan gate

**Files:**
- Create: `scripts/mobsf_scan.py`, `scripts/test_mobsf_scan.py`, `security/mobsf-baseline.json`, a `mobsf` job in `saraswati-ci.yml`

**Interfaces:**
- Produces: `def high_findings(report: dict) -> list[str]` (titles of High-severity findings), `def new_findings(report: dict, baseline: dict) -> list[str]` (High findings not in the baseline). The CI job pulls the MobSF image **by digest**, starts it as a service, uploads the unsigned APK through the REST API, fetches the JSON report, and runs the script.

- [ ] **Step 1: Write failing tests** with small fixture reports: `high_findings lists only high severity`, `new_findings ignores baselined titles`, `new_findings reports a new high finding`, `empty report yields no findings`. Run. Expected: FAIL; implement; PASS.
- [ ] **Step 2: Add the CI job in report-only mode** (always uploads the report as an artifact, does not fail). Pin the image digest after `docker pull`.
- [ ] **Step 3: Triage the first report with the user's help.** Every High finding is either fixed, or recorded in `security/mobsf-baseline.json` with a one-line reason in `docs/security-audit.md`.
- [ ] **Step 4: Switch the job to gating** (fail on `new_findings`). Prove it bites by temporarily adding a known-bad pattern on a throwaway branch.
- [ ] **Step 5: Commit.** `ci: MobSF scan gate with triaged baseline`.

### Task 11: Macrobenchmark and Baseline Profile module

**Files:**
- Create: `baselineprofile/build.gradle.kts` and sources under `baselineprofile/src/main/kotlin/...` (`StartupBenchmark`, `HomeScrollBenchmark`, `OpenPlayerBenchmark`, `BaselineProfileGenerator`), `app/src/main/baseline-prof.txt` (generated, committed)
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts` (plugin and dependency lines only), `gradle/verification-metadata.xml`, `docs/perf-baseline.md`

**Interfaces:**
- Produces: a `com.android.test` module targeting `:app` (flavor `gms`) using the androidx baselineprofile Gradle plugin. Pin the plugin and `androidx.benchmark:benchmark-macro-junit4` to the latest stable versions at execution time (record them in the version catalog). Benchmarks run with `CompilationMode.None()` and `CompilationMode.Partial()` so the numbers reflect a freshly sideloaded app (whose profile is only compiled later, when the phone is idle and charging).

- [ ] **Step 1: Create the module** following the official Baseline Profile setup for the installed AGP; run `sar_gradle :baselineprofile:tasks --all` to confirm the benchmark and profile tasks exist. Expected: tasks listed.
- [ ] **Step 2: Write the benchmarks and generator** (launch, scroll Home, open search, open a playlist, play, open and close the player). They require a signed-in app, so they run against the app already installed on the Pixel (the benchmark variant is debug-key signed, the same as the Phase 1 local build, so data is preserved).
- [ ] **Step 3: Generate the profile** on the Pixel: `sar_gradle :app:generateGmsBaselineProfile` (name per the plugin). Commit `app/src/main/baseline-prof.txt`.
- [ ] **Step 4: Measure.** Run the startup and scroll benchmarks in both compilation modes against the budgets from the design (cold start ≥20% faster than Metrolist 13.6.3 and ≤1.0 s; jank <1%). Update `docs/perf-baseline.md` with before/after tables and say plainly which budgets are met. If the freshly installed (`None`) case misses the cold-start budget, record that as input for Phase 5's speed work; do not add a `.dm` delivery path in this phase.
- [ ] **Step 5: Run all suites.** Expected: PASS.
- [ ] **Step 6: Commit.** `perf: macrobenchmarks and baseline profile`.

### Task 12: Crash and playback-failure reporting (optional; skippable without affecting anything else)

**Files:**
- Create: `saraswati/telemetry/Scrubber.kt`, `app/src/test/kotlin/com/metrolist/music/saraswati/telemetry/ScrubberTest.kt`
- Modify: `App.kt` (initialize), `gradle/libs.versions.toml` (Sentry Android, pinned), `security/egress-allowlist.json` and `network_security_config.xml` (the ingest host), `security/manifest-allowlist.json` (only if the SDK merges a new entry; review it), `gradle/verification-metadata.xml`

**Interfaces:**
- Produces: `object Scrubber { fun scrubUrl(url: String): String; fun scrubMessage(text: String): String; fun redactHeaders(headers: Map<String, String>): Map<String, String> }`. Sentry options: `sendDefaultPii=false`, screenshots and view-hierarchy off, breadcrumbs from UI and network off, `beforeSend` applying the scrubber to every string and dropping the `request`, `user` and `device` identifiers; auto-init disabled; initialize in `App.onCreate` only when `BuildConfig.SENTRY_DSN` is non-empty. Playback failures are sent as non-fatal events tagged with the YouTube client name and HTTP status only.

- [ ] **Step 1: HUMAN CHECKPOINT.** Ask the user to create a free Sentry project (platform Android) and give you only the DSN (it is a public client key, safe to commit). If they decline, mark the task skipped and move on.
- [ ] **Step 2: Write failing tests** for `Scrubber`: `video ids and tokens in urls are removed`, `cookie and authorization headers are redacted`, `SAPISID and visitor data patterns are removed from free text`, `ordinary text is unchanged`. Run; implement; PASS.
- [ ] **Step 3: Wire it in,** regenerate verification metadata, review the merged manifest diff with `apk_gate.py check`, add the ingest host to the egress allowlist.
- [ ] **Step 4: Verify on the Pixel** with a debug-only forced test event; confirm the event appears in Sentry without cookies, full URLs or device ids.
- [ ] **Step 5: Commit.** `feat: scrubbed crash and playback-failure reporting`.

### Task 13: Developer verification (human-assisted)

**Files:**
- Create: `docs/developer-verification.md`

- [ ] **Step 1: Read the official pages** `https://developer.android.com/developer-verification/guides/limited-distribution` and `.../faq`, and write exactly what they require into `docs/developer-verification.md` (accounts, what is registered, how recipient devices are authorized, any proof of key ownership, device cap of 20, enforcement dates: 2026-09-30 in Brazil, Indonesia, Singapore and Thailand; global in 2027). Quote nothing that you did not read.
- [ ] **Step 2: Prepare the registration sheet:** both package names and the release certificate SHA-256 (from `security/release-cert.sha256`).
- [ ] **Step 3: HUMAN CHECKPOINT.** The user signs in to the Android Developer Console with the Google account that has 2-Step Verification and a payments profile, and registers both packages. The agent never handles that sign-in.
- [ ] **Step 4: Record the device-authorization procedure** (QR code or link per phone) in the runbook so Phase 3's rollout can authorize each phone as it is set up.
- [ ] **Step 5: Commit.** `docs: developer verification procedure and registration record`.

## Self-review notes

- Spec coverage: signing key and protected environment (Tasks 1, 6); CI gates (2, 3, 10); Updater app and manual routine including Samsung steps (4, 5, 8); banner (7); sync bot (9); Baseline Profile (11); Sentry (12); developer verification (13). Phase 3 owns the family hub, Saral, setup guide on Pages and per-phone rollout.
- Deliberate deviations from the design text, to veto: no `.dm` delivery (the embedded profile is compiled by Android when the phone is idle); the sync bot uses the default workflow token plus a CI dispatch instead of a GitHub App; version codes come from the git commit count rather than a CI counter; a shared `:update-core` module instead of a single `:updater-app` module; Sentry is optional and last.

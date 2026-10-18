# Saraswati Phase 1: Fork, Harden, Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Metrolist at a pinned, reviewed upstream commit into "Saraswati 0.1": rebranded, with risky capabilities removed, hardened configuration, enforced permission/component/network allowlists, a written security audit, and measured speed and Android Auto baselines, installed beside Metrolist on the user's Pixel.

**Architecture:** A thin overlay on upstream. Identity and flags are small edits marked `// SARASWATI:`. Capability removal is a flavor manifest overlay (`app/src/gms/AndroidManifest.xml`) plus hidden UI entry points. New logic lives in `com.metrolist.music.saraswati.*`. Compliance is enforced by stdlib-only Python gate scripts under `security/` that read the **built APK**, so they keep working when upstream code changes.

**Tech Stack:** Kotlin 2.4.20 / Compose, Gradle 9.7.1 + AGP 9.4.x, JDK 21, Media3 1.10.1, Room, JUnit4 + Robolectric (JVM tests), Python 3 stdlib `unittest` (gates), Android SDK tools (`apkanalyzer`, `apksigner`, `aapt2`), adb, REA CLI (`npx -y rea-agents@6.5.0`).

**Spec:** `/home/mohit/.claude/plans/i-am-creating-my-serene-engelbart.md` (copied into the repo in Task 1). Phase 2 is `2026-10-17-saraswati-phase2-updates-pipeline.md`.

## Facts established by a spike (do not re-derive)

- The pinned base builds and passes: `:app:assembleGmsDebug :app:testGmsDebugUnitTest` → BUILD SUCCESSFUL in ~3 min, **41 test classes, 186 tests, 0 failures**, Gradle cache ~1.8 GB.
- A fresh machine fails at `:app:validateSigningGmsDebug` ("Keystore file `~/.android/debug.keystore` not found") because upstream hard-wires that path. Task 2 creates it. The key already exists on this machine.
- `v13.7.0` pins InnerTubeX **v0.5.2**; the base commit pins **v0.8.4** (two playback-fix bumps landed after the tag). Never base on the tag.
- Upstream's network config allows **cleartext to every host**; backup rules exclude only the music cache (so cloud backup and phone-to-phone transfer include the sign-in cookie); `onConnect` accepts **every** caller; FileProvider exposes `path="."` for external storage and the whole cache; `settings.gradle.kts` pulls from `maven.aliyun.com`, `mavenLocal()` and unrestricted JitPack; the Gradle wrapper has no checksum; no dependency verification exists.
- The app names ~45 hosts in source; the family needs about 10 domains (Task 12).

## Global Constraints

- **Identity:** `applicationId` `dev.diamondspark.saraswati`; debug build `dev.diamondspark.saraswati.debug` (suffix `.debug` always); app name `Saraswati`, debug `Saraswati Debug`; Kotlin `namespace` stays `com.metrolist.music` (keeps the diff small); new code in `com.metrolist.music.saraswati.*`.
- **Base commit:** upstream `main` @ `76fa5a78b5110674442884b6f21c0cef51cdb782` (2026-10-08, InnerTubeX `v0.8.4`, Media3 `1.10.1` with upstream's comment "1.11.1 hides Android 17 media controls (#4404)"). Media3 stays at 1.10.1.
- **SDK/toolchain:** compileSdk 37, targetSdk 36, minSdk 26, JDK 21 (`/home/mohit/tools/jdk-21`), Android SDK `/home/mohit/Android/Sdk`, adb `/home/mohit/Android/Sdk/platform-tools/adb` (not `/usr/bin/adb`), test phone Pixel 8 Pro serial `<PIXEL_SERIAL>` (Android 17). `apksigner` needs `java` on PATH.
- **Shipped variant:** flavor `gms` only; ABI `arm64-v8a` only (all family phones are 2022+; Task 14 records each phone's `ro.product.cpu.abilist` before rollout and adds `armeabi-v7a` only if one lacks arm64).
- **Final permission allowlist (Saraswati app):** `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`, `dev.diamondspark.saraswati.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, plus any permission the `gms`-only libraries merge in, each recorded and justified in Task 4. **Never present:** `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, SMS, contacts, location, phone state, camera, accessibility, notification-listener, overlay.
- **Network:** HTTPS only. No user-installed CAs, in any build type.
- **Secrets:** never commit, print or log secrets. Never print `~/.claude.json` `mcpServers` URLs (the Penpot entry embeds a token) and never run `claude mcp list`.
- **Upstream edits:** every edit to an upstream-owned file carries a `// SARASWATI:` (Kotlin/Gradle) or `<!-- SARASWATI: -->` (XML) marker with a reason. Keep every upstream copyright/GPL header; credit Metrolist, InnerTune and OuterTune.
- **Machine limits:** ~8 GB RAM free. Always build through `sar_gradle` (Task 2): Gradle heap 3 GB, Kotlin daemon 2 GB, `nice -n 10`.
- **Commits:** Conventional Commits, one per task step noted, each ending with the attribution trailer the harness supplies at execution time (currently `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`). Work on branch `phase1-harden`; Task 14 merges it.
- **Test commands:** JVM `source scripts/dev-env.sh && sar_gradle :app:testGmsDebugUnitTest`; Python `python3 -I -m unittest discover -s security -p 'test_*.py' -v` and the same with `-s scripts`.
- **Authorization:** the approved design decides "public repo `diamondspark/saraswati`" and "no secrets in the repo". Push nothing else, and do not enable GitHub Actions in this phase (Phase 2 does).

## Review Focus

Inputs and failure modes the spec implies but no feature task would otherwise exercise, most likely first. Each has a test in its owning task.

1. **Android Auto / Bluetooth / Assistant stop connecting** after the manifest, permission or controller-policy changes (a connecting package we did not allow). Owners: Task 8 (policy unit tests, denial logging), Task 13 (Desktop Head Unit run with zero Android Auto denials).
2. **Sign-in, album art or lyrics silently break** under the deny-by-default network policy (a Google or image host missing from the allowlist: login loops, blank art). Owner: Task 12 (instrumented egress test, scripted sign-in, art and lyrics checks).
3. **A removed feature leaves a live entry point** (the old recognizer intent, a recognition button, the Integrations rows, the Alarm and AI settings) that opens a dead screen or crashes. Owner: Task 5.
4. **A restored Metrolist backup (or any legacy file) puts a plaintext cookie or integration token into settings.** Owner: Task 7 (startup scrubber).
5. **A shallow clone produces a tiny `versionCode`**, which Android then rejects as a downgrade. Owner: Task 3.

---

### Task 1: Fork, clone and pin the base commit

**Files:**
- Create (repo): `README.md`, `CREDITS.md`, `SECURITY.md`, `docs/superpowers/specs/2026-10-17-saraswati-design.md`, `docs/superpowers/plans/2026-10-17-saraswati-phase1-fork-harden.md`, `docs/superpowers/plans/2026-10-17-saraswati-phase2-updates-pipeline.md`
- Delete (repo): `.github/workflows/build.yml`, `build_pr.yml`, `build_quick.yml`, `release.yml`, and any `.github/FUNDING.yml`
- Move: upstream `README.md` → `docs/UPSTREAM_README.md` (keeps attribution)

**Interfaces:**
- Produces: remotes `origin` = `diamondspark/saraswati`, `upstream` = `MetrolistGroup/Metrolist`; local path `/home/mohit/saraswati`; branch `main` at the pinned commit plus our docs commit; branch `upstream-main` at the pinned commit (mirror used by Phase 2's sync bot); working branch `phase1-harden`.

- [ ] **Step 1: Create the public fork.** `gh repo fork MetrolistGroup/Metrolist --fork-name saraswati --clone=false`. Verify: `gh repo view diamondspark/saraswati --json isFork,visibility,parent --jq '[.isFork,.visibility,.parent.name]|@tsv'` prints `true	PUBLIC	Metrolist`.
- [ ] **Step 2: Clone with full history.** `gh repo clone diamondspark/saraswati /home/mohit/saraswati`, then `git -C /home/mohit/saraswati remote add upstream https://github.com/MetrolistGroup/Metrolist.git && git -C /home/mohit/saraswati fetch upstream`. Verify `git rev-parse --is-shallow-repository` prints `false`.
- [ ] **Step 3: Pin.** `git checkout -B main 76fa5a78b5110674442884b6f21c0cef51cdb782 && git branch -f upstream-main 76fa5a78b5110674442884b6f21c0cef51cdb782`. Verify `git log -1 --format=%H` equals that hash and `grep -E '^innertubex = "v0.8.4"' gradle/libs.versions.toml` matches.
- [ ] **Step 4: Remove upstream CI and add project docs.** Delete the four workflow files (upstream workflows run on a third-party runner provider with signing secrets; deleting is safer than disabling because a disabled flag does not stop a newly added upstream workflow). Add `README.md` (what Saraswati is, "fork of Metrolist, GPL-3.0", build pointer), `CREDITS.md` (Metrolist, InnerTune, OuterTune, copied from upstream's credits), `SECURITY.md` (how to report, scope: family-only app), and the three copied docs. Commit: `chore: pin upstream base 76fa5a7, drop upstream CI, add project docs`.
- [ ] **Step 5: Publish.** `git push --force-with-lease origin main upstream-main` (the fork is brand new, so moving `main` back to the pinned commit is safe). Verify `gh api repos/diamondspark/saraswati/branches/main --jq .commit.sha` starts with the docs commit, and `git branch -r --contains` shows no extra branches.
- [ ] **Step 6: Repository security settings.** Run `gh api -X PATCH repos/diamondspark/saraswati --input -` with `{"has_wiki":false,"has_projects":false,"security_and_analysis":{"secret_scanning":{"status":"enabled"},"secret_scanning_push_protection":{"status":"enabled"}}}`, then `gh api -X PUT repos/diamondspark/saraswati/vulnerability-alerts` and `gh api -X PUT repos/diamondspark/saraswati/automated-security-fixes`. Verify `gh api repos/diamondspark/saraswati --jq .security_and_analysis` shows both secret-scanning features `enabled`.
- [ ] **Step 7: Start the working branch.** `git checkout -b phase1-harden`. No commit.

### Task 2: Reproducible toolchain script and a green baseline build

**Files:**
- Create: `scripts/dev-env.sh`, `docs/build-baseline.md`

**Interfaces:**
- Produces (sourced shell): exports `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `REA_JADX_MCP_JAR`, `REA_ADB_PATH`, and prepends `$JAVA_HOME/bin` and `$ANDROID_HOME/platform-tools` (and `cmdline-tools/latest/bin`, `build-tools/37.0.0`) to `PATH`. Defines `sar_gradle` (runs `./gradlew --console=plain "-Dorg.gradle.jvmargs=-Xmx3g -Dkotlin.daemon.jvm.options=-Xmx2g -XX:+UseParallelGC" "$@"` under `nice -n 10`) and `sar_ensure_debug_keystore` (creates `~/.android/debug.keystore` with `keytool -genkeypair -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"` only if missing, mode 600).
- Consumes: nothing.

- [ ] **Step 1: Write `scripts/dev-env.sh`** with the interface above. Verification (the "failing test" is the environment itself): in a fresh shell, `source scripts/dev-env.sh; java -version 2>&1 | head -1; adb version | sed -n 2p; apksigner --version`. Expected: `openjdk version "21.0.12.1"`, `Version 37.0.1`, and an apksigner version with no "java: not found".
- [ ] **Step 2: Build the baseline.** `source scripts/dev-env.sh && sar_ensure_debug_keystore && sar_gradle :app:assembleGmsDebug :app:testGmsDebugUnitTest`. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 3: Record totals.** Sum `tests`, `failures`, `errors`, `skipped` from `app/build/test-results/testGmsDebugUnitTest/*.xml` (stdlib `xml.etree`, run with `python3 -I`). Expected: 186 tests, 0 failures, 0 errors.
- [ ] **Step 4: Write `docs/build-baseline.md`:** commit hash, command, wall time, test totals, debug APK path (`app/build/outputs/apk/gms/debug/app-gms-debug.apk`) and size, and the keystore pitfall. If any test fails here, list it as "known upstream failure" and do not edit it.
- [ ] **Step 5: Commit.** `chore: add dev-env script and record baseline build`.

### Task 3: Identity: rebrand, version scheme, ABI, flavor defaults

**Files:**
- Create: `scripts/version.py`, `scripts/test_version.py`, `app/src/test/kotlin/com/metrolist/music/saraswati/BuildIdentityTest.kt`
- Modify: `app/build.gradle.kts` (only line 10 `baseApplicationId`, line 48 default app name, line 136 debug app name, plus ONE new `android { ... }` block appended at the end of the file; upstream bumps `versionCode`/`versionName` and edits flavors and ABIs, so those lines are overridden by reassignment, never edited), `app/src/main/kotlin/com/metrolist/music/ui/theme/Theme.kt:28`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_launcher_static_foreground.png` and the v31 background/foreground XMLs (placeholder marigold monogram), `app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AboutScreen.kt`

**Interfaces:**
- Produces: `scripts/version.py` with `def version_code(commit_count: int, is_shallow: bool) -> int` (returns `commit_count`; raises `ValueError` if `is_shallow` or `commit_count < 1`) and a CLI that prints the code for the current repo (exit 2 on error). Gradle reads it via `providers.exec`. `BuildConfig.APPLICATION_ID`, `BuildConfig.VERSION_CODE`, `BuildConfig.ARCHITECTURE`, `BuildConfig.UPDATER_AVAILABLE`, `BuildConfig.CAST_AVAILABLE`.

- [ ] **Step 1: Write the failing Python test** `scripts/test_version.py`: `test_returns_commit_count` (`version_code(4100, False) == 4100`), `test_rejects_shallow_clone` (`ValueError` for `version_code(1, True)`), `test_rejects_zero_commits` (`ValueError`). Run `python3 -I -m unittest discover -s scripts -p 'test_*.py' -v`. Expected: FAIL (module missing).
- [ ] **Step 2: Implement `scripts/version.py`.** CLI shells out to `git rev-list --count HEAD` and `git rev-parse --is-shallow-repository`. Re-run. Expected: 3 tests PASS.
- [ ] **Step 3: Write the failing Kotlin test** `BuildIdentityTest`: `assertEquals("dev.diamondspark.saraswati.debug", BuildConfig.APPLICATION_ID)`, `assertEquals("arm64", BuildConfig.ARCHITECTURE)`, `assertFalse(BuildConfig.UPDATER_AVAILABLE)`, `assertTrue(BuildConfig.CAST_AVAILABLE)`, `assertTrue(BuildConfig.VERSION_CODE > 153)`. Run `sar_gradle :app:testGmsDebugUnitTest --tests '*BuildIdentityTest'`. Expected: FAIL (assertion on APPLICATION_ID).
- [ ] **Step 4: Edit `app/build.gradle.kts`.** Three single-line edits: `baseApplicationId = "dev.diamondspark.saraswati"` (line 10); default name `appNameOverride ?: "Saraswati"` (line 48); debug name `"Saraswati Debug"` (line 136). Leave the debug `applicationIdSuffix` logic alone (the `METROLIST_APPLICATION_ID` variable stays unset, so `.debug` always applies). Then append one block at the end of the file, marked `// SARASWATI:`, that overrides by reassignment: `android { defaultConfig { versionCode = <int printed by scripts/version.py, read with providers.exec>; versionName = "${versionName}-s$versionCode"; ndk { abiFilters.clear(); abiFilters += "arm64-v8a" }; buildConfigField("String", "ARCHITECTURE", "\"arm64\"") }; productFlavors { getByName("gms") { buildConfigField("Boolean", "UPDATER_AVAILABLE", "false") } } }`. (`versionName` on the right-hand side is upstream's `13.7.0`.) Do not touch `isDefault`; every command in this plan names the `Gms` variant explicitly.
- [ ] **Step 5: Run the new test and the full suite.** Expected: `BuildIdentityTest` PASS; 187 tests, 0 failures. Then confirm the reassignments really won: `grep -E 'VERSION_(CODE|NAME)|ARCHITECTURE|UPDATER_AVAILABLE' app/build/generated/source/buildConfig/gms/debug/com/metrolist/music/BuildConfig.java` shows the new code, a `13.7.0-s<code>` name, `"arm64"` and `false` (upstream's `BuildConfig` does generate `VERSION_CODE` and `VERSION_NAME`, and the About screen and crash reports print them).
- [ ] **Step 6: Placeholder icon, accent and credits.** Replace launcher icon resources with a simple marigold (`#F5B301`) monogram so Saraswati is distinguishable from Metrolist side by side (final veena/lotus mark comes with the Phase 3 design pass); set `DefaultThemeColor` to `Color(0xFFF5B301)` (note: with the default color Android 12+ still uses wallpaper colors, by upstream design); in `AboutScreen.kt` replace Metrolist's donate/social links with "Saraswati is a fork of Metrolist (GPL-3.0)", the repo URL, and the three credits. Verify `sar_gradle :app:assembleGmsDebug` succeeds.
- [ ] **Step 7: Commit.** `feat: Saraswati identity, git-count versionCode, arm64-only, gms default`.

### Task 4: Manifest overlay and the APK gate

**Files:**
- Create: `app/src/gms/AndroidManifest.xml`, `security/apk_gate.py`, `security/test_apk_gate.py`, `security/manifest-allowlist.json`, `security/fixtures/clean_manifest.xml`, `security/fixtures/bad_manifest.xml`

**Interfaces:**
- Produces in `security/apk_gate.py`:
  - `@dataclass(frozen=True) class ManifestFacts(package: str, permissions: frozenset[str], exported_components: frozenset[str], allow_backup: bool | None, debuggable: bool | None)` where components are written `"<kind>:<fully.qualified.Name>"` (`activity`, `activity-alias`, `service`, `receiver`, `provider`) and a component counts as exported only when `android:exported="true"`.
  - `def parse_manifest(xml_text: str) -> ManifestFacts`
  - `def check(facts: ManifestFacts, allowlist: dict) -> list[str]`: violations, empty means pass. Exact strings: `unexpected permission: <p>`, `forbidden permission: <p>`, `unexpected exported component: <c>`, `allowBackup must be false`, `debuggable must not be true`, `package mismatch: expected <a>, found <b>`.
  - CLI: `python3 -I security/apk_gate.py check --apk <apk> --allowlist security/manifest-allowlist.json` (runs `apkanalyzer manifest print`; exit 0 pass, 1 violations, 2 usage), `... check --xml <file>` for fixtures, `... snapshot --apk <apk>` prints the facts as sorted JSON.
  - Allowlist JSON keys: `package`, `permissions`, `exported_components`, `forbidden_permissions`.

- [ ] **Step 1: Write failing tests** in `security/test_apk_gate.py` with fixtures: `test_parse_extracts_permissions_and_exported_components` (clean fixture → expected sets), `test_check_passes_clean_manifest` (`[] == check(...)`), `test_check_flags_unexpected_permission`, `test_check_flags_forbidden_permission_even_when_allowlisted`, `test_check_flags_unexpected_exported_component`, `test_check_flags_allow_backup_true`, `test_check_flags_package_mismatch`. Run the Python suite. Expected: FAIL (module missing).
- [ ] **Step 2: Implement `apk_gate.py`** per the interface (use `xml.etree.ElementTree`; the `android` namespace is `http://schemas.android.com/apk/res/android`). Run. Expected: all PASS.
- [ ] **Step 3: Write `app/src/gms/AndroidManifest.xml`.** `<manifest>` with `xmlns:tools`; `tools:node="remove"` for `uses-permission` `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` and the microphone `uses-feature`; `<application android:allowBackup="false" tools:replace="android:allowBackup">` containing `tools:node="remove"` entries (fully-qualified names) for `com.metrolist.music.discord.DiscordOAuthActivity`, `...recognition.RecognitionLaunchActivity`, `...recognition.RecognitionForegroundService`, `...quicksettings.MusicRecognizerTileService`, `...widget.MusicRecognizerWidgetReceiver`, `...playback.alarm.MusicAlarmReceiver`, `...playback.alarm.MusicAlarmRescheduleReceiver`; and inside `com.metrolist.music.MainActivity` two `<intent-filter tools:node="remove">` elements whose content exactly matches upstream's `com.metrolist.music.action.RECOGNITION` filter and its `https://metrolist.cc/listen` invite filter. Leave the NFC `NDEF_DISCOVERED` actions alone.
- [ ] **Step 4: Build unsigned release and snapshot.** `sar_gradle :app:assembleGmsRelease`, then `python3 -I security/apk_gate.py snapshot --apk app/build/outputs/apk/gms/release/app-gms-release-unsigned.apk`. Expected: package `dev.diamondspark.saraswati`, none of the removed permissions or components.
- [ ] **Step 5: Fill `security/manifest-allowlist.json` from the snapshot, deliberately.** Review every permission and exported component line by line (library-added ones from Cast/Play-services and `androidx.profileinstaller.ProfileInstallReceiver` included); write a one-line reason for each non-obvious entry in `docs/build-baseline.md`. `forbidden_permissions` is exactly the "Never present" list from Global Constraints. Run `check --apk ...`. Expected: exit 0.
- [ ] **Step 6: Prove the gate bites.** Temporarily add `<uses-permission android:name="android.permission.RECORD_AUDIO"/>` to the overlay, rebuild, run `check`. Expected: exit 1 with `forbidden permission: android.permission.RECORD_AUDIO`. Revert.
- [ ] **Step 7: Commit.** `feat(security): manifest overlay removing mic/alarm/boot/Discord/recognizer, APK gate`.

### Task 5: Hide removed features, limit lyrics providers

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/saraswati/SaraswatiFeatures.kt`, `app/src/test/kotlin/com/metrolist/music/saraswati/SaraswatiFeaturesTest.kt`, `app/src/test/kotlin/com/metrolist/music/saraswati/LyricsPolicyTest.kt`, `docs/strip-checklist.md`
- Modify: `ui/screens/settings/AccountSettings.kt:398` (Integrations entry), `ui/screens/settings/SettingsScreen.kt:108` (AI row), the Alarm settings entry (find with `grep -rn "AlarmSettings(" app/src/main/kotlin/com/metrolist/music/ui`), the six recognition call sites (`ui/screens/HomeScreen.kt:2608`, `MainActivity.kt:1195,1325,1541`, `ui/screens/search/OnlineSearchResult.kt:573`, `ui/screens/search/SearchScreen.kt:311`), `MainActivity.kt` `ACTION_RECOGNITION` handler (~1532-1545, early return), `lyrics/LyricsProviderRegistry.kt:9` (`providerMap`)

**Interfaces:**
- Produces: `object SaraswatiFeatures { const val DISCORD = false; const val LASTFM = false; const val AI_LYRICS = false; const val RECOGNITION = false; const val ALARMS = false; val ALLOWED_LYRICS_PROVIDER_KEYS: Set<String> }` where the allowed keys are exactly `BetterLyrics`, `LrcLib`, `YouTubeSubtitle`, `YouTube` (registry keys, not display names). Listen Together stays visible but cannot connect until Phase 4 (it is woven through `HomeScreen`; the network policy blocks its third-party server).

- [ ] **Step 1: Write failing tests.** `SaraswatiFeaturesTest`: `assertFalse` for each of the five flags. `LyricsPolicyTest`: `assertEquals(setOf("BetterLyrics","LrcLib","YouTubeSubtitle","YouTube"), LyricsProviderRegistry.providerNames.toSet())`, `assertEquals(4, LyricsProviderRegistry.getDefaultProviderOrder().mapNotNull { LyricsProviderRegistry.getProviderByName(it) }.size)`, and `assertEquals(emptyList<String>(), LyricsProviderRegistry.deserializeProviderOrder("Kugou,Zemer,Paxsenix").filter { it in listOf("KuGou","Zemer","Paxsenix") })`. Run. Expected: FAIL.
- [ ] **Step 2: Implement `SaraswatiFeatures`** and filter `providerMap` with `.filterKeys { it in SaraswatiFeatures.ALLOWED_LYRICS_PROVIDER_KEYS }`. Run the two tests. Expected: PASS. Reason for the policy (document in `docs/strip-checklist.md`): each extra lyrics host learns every song the family plays; KuGou, Paxsenix, LyricsPlus (six community mirrors) and Zemer (which also calls `jewishmusic.fm:8443`) are dropped; re-add later by adding the key and its host to the egress allowlist.
- [ ] **Step 3: Wrap the entry points** with `if (SaraswatiFeatures.X)` (Integrations row, AI row, Alarm row, the six recognition sites). In `MainActivity`'s `ACTION_RECOGNITION` handler return immediately when `!SaraswatiFeatures.RECOGNITION` (the intent filter is gone but an explicit component start would still reach it). Do not edit `MusicService`, `NavigationBuilder` or the removed packages (unreachable code stays; that keeps merges clean).
- [ ] **Step 4: Compile and run the full suite.** Expected: BUILD SUCCESSFUL, all tests PASS.
- [ ] **Step 5: Write `docs/strip-checklist.md`** listing each removed feature, the entry points hidden, the manifest entries removed, and a manual click-through list for Task 10 (open Settings → every row; Search; Home top bar; a Metrolist-style `adb shell am start -a com.metrolist.music.action.RECOGNITION -n dev.diamondspark.saraswati.debug/com.metrolist.music.MainActivity` must open the app normally with no recognition screen and no crash).
- [ ] **Step 6: Commit.** `feat: hide Discord/Last.fm/AI/recognizer/alarm entry points, restrict lyrics providers`.

### Task 6: Platform configuration: cleartext off, backup closed, FileProvider narrowed

**Files:**
- Create: `app/src/test/kotlin/com/metrolist/music/saraswati/PlatformConfigTest.kt`
- Modify: `app/src/main/res/xml/network_security_config.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, `provider_paths.xml`

**Interfaces:**
- Produces: `PlatformConfigTest` reads the four XML files from `src/main/res/xml/` with `javax.xml.parsers` (relative to the module directory) and asserts the rules below. `allowBackup=false` is enforced by the APK gate (Task 4); the XML rules below are the second layer if it is ever flipped.

- [ ] **Step 1: Write failing tests.** `cleartext is not permitted anywhere` (`base-config cleartextTrafficPermitted == "false"` and no `domain-config` with `"true"`); `backup rules exclude datastore, databases and shared prefs` (in both `backup_rules.xml` and the `cloud-backup` and `device-transfer` sections of `data_extraction_rules.xml`: exclude `domain="file" path="datastore"`, `domain="database" path="."`, `domain="sharedpref" path="."`, plus the existing exoplayer/download excludes); `file provider exposes only named subdirectories` (no attribute `path="."`, no `<root-path>` in `provider_paths.xml`). Run. Expected: FAIL (all three).
- [ ] **Step 2: Edit the XML.** `network_security_config.xml`: base-config `cleartextTrafficPermitted="false"`, system trust anchors only, with a `<!-- SARASWATI: ... -->` comment. Backup files: add the excludes. `provider_paths.xml`: inspect the four call sites (`utils/PlaylistExporter.kt:184`, `utils/ComposeToImage.kt:627`, `ui/screens/CrashActivity.kt:82`, `ui/screens/playlist/LocalPlaylistScreen.kt:927`), record each shared file's directory, and replace the three `path="."` entries with the minimal subdirectories they use (for example `cache-path` `path="shared/"`), keeping `Documents/MetrolistExports/` for `external-files-path`. Where a call site writes to the cache root, change it to write into the new subdirectory (marked `// SARASWATI:`).
- [ ] **Step 3: Run the new tests and the full suite.** Expected: PASS.
- [ ] **Step 4: Commit.** `fix(security): https only, close backup paths, narrow FileProvider`.

### Task 7: Secrets: cookie vault, secret-key coverage guard, startup scrubber

**Files:**
- Create: `saraswati/security/AesGcmKeyProvider.kt`, `CookieVault.kt`, `SecretPreferenceKeys.kt`, `SecretScrubber.kt` (under `app/src/main/kotlin/com/metrolist/music/`); tests `CookieVaultTest.kt`, `SecretKeyCoverageTest.kt`, `SecretScrubberTest.kt` (under `app/src/test/kotlin/com/metrolist/music/saraswati/security/`)
- Modify: `utils/DataStore.kt:70` (`get`) and `:96` (`rememberPreference`), `App.kt:241` (cookie read), `SyncUtils.kt:279` (cookie read), `ui/screens/LoginScreen.kt:193` (cookie write), `App.kt` startup (call the scrubber)

**Interfaces:**
- Produces:
  - `interface AesGcmKeyProvider { fun key(): javax.crypto.SecretKey }`; `class AndroidKeystoreKeyProvider(alias: String = "saraswati_cookie_v1") : AesGcmKeyProvider` (AES-256, GCM, no padding, `setIsStrongBoxBacked(true)` with fallback to the default hardware-backed key when StrongBox is unavailable, no user authentication required); `class SoftwareKeyProvider : AesGcmKeyProvider` (JVM tests).
  - `class CookieVault(keys: AesGcmKeyProvider) { fun seal(plain: String): String; fun open(stored: String): String? }`: format `"v1:" + Base64(iv12 || ciphertext || tag)`, AAD `"saraswati.cookie.v1"`; `open` returns null for anything not `v1:`, tampered, or sealed under another key.
  - `object SecretPreferenceKeys { val all: Set<Preferences.Key<*>> }` containing `InnerTubeCookieKey` and every Discord, Last.fm and AI-service token/key preference.
  - `object SecretScrubber { suspend fun scrub(dataStore: DataStore<Preferences>, vault: CookieVault) }`: removes every key in `SecretPreferenceKeys.all` except an `InnerTubeCookieKey` value that `vault.open` accepts.

- [ ] **Step 1: Write failing tests.** `CookieVaultTest`: `round trip returns the original`, `sealed text contains no cookie fragment` (`"SAPISID=abc"` not a substring), `tampered ciphertext opens to null`, `a different key opens to null`, `unprefixed legacy plaintext opens to null`, `empty string round-trips`. `SecretKeyCoverageTest`: reflect over the static `Preferences.Key` fields of `com.metrolist.music.constants.PreferenceKeysKt`; every field whose name matches `(?i)(cookie|token|apikey|api_key|secret|session|password)` must be in `SecretPreferenceKeys.all` or in an explicit `NOT_SECRET` set with a reason (this makes any key added by a future upstream merge a conscious decision). `SecretScrubberTest` (use `mutablePreferencesOf`/an in-memory store): `drops a plaintext cookie`, `drops integration tokens`, `keeps a vault-sealed cookie`, `keeps unrelated preferences`. Run. Expected: FAIL (classes missing).
- [ ] **Step 2: Implement the four classes** per the interface; unit tests use `SoftwareKeyProvider`.
- [ ] **Step 3: Route the cookie through the vault.** In `DataStore.kt`, make `get` and `rememberPreference` return `vault.open(stored)` for `InnerTubeCookieKey`, and make writes store `vault.seal(value)`; update the three direct sites above the same way. Call `SecretScrubber.scrub` in `App.onCreate` before the cookie is read. Mark each edit `// SARASWATI:`.
- [ ] **Step 4: Run the new tests and the full suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(security): Keystore-sealed sign-in cookie, secret-key guard, startup scrubber`.
- [ ] **Step 6 (verified on device in Task 10):** after signing in on the debug build, `adb exec-out run-as dev.diamondspark.saraswati.debug cat files/datastore/settings.preferences_pb | grep -c SAPISID` prints `0`.

### Task 8: Controller policy for the music service

**Files:**
- Create: `saraswati/security/ControllerPolicy.kt`, `app/src/test/kotlin/com/metrolist/music/saraswati/security/ControllerPolicyTest.kt`
- Modify: `playback/MediaLibrarySessionCallback.kt` (`onConnect` ~94, `onGetLibraryRoot` ~141), `app/src/main/AndroidManifest.xml` is not edited (the overlay adds `<queries>` if needed)

**Interfaces:**
- Produces:
  - `data class CallerInfo(val packageName: String, val isTrusted: Boolean, val isSystemApp: Boolean, val signingSha256: Set<String>)`
  - `object ControllerPolicy { const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"; fun isAllowed(selfPackage: String, caller: CallerInfo?, expectedDigests: Map<String, Set<String>>): Boolean }`. Rules, in order: `caller == null` (package not visible or unknown) → deny; own package → allow; `isTrusted` → allow; package in `expectedDigests` → allow only if `signingSha256` intersects its digests; `isSystemApp` → allow; otherwise deny.
- Consumes: `MediaSession.ControllerInfo` (`packageName`, `isTrusted`).

- [ ] **Step 1: Write failing tests** `ControllerPolicyTest`: `own package is allowed`, `trusted controller is allowed`, `system app is allowed`, `Android Auto with the expected digest is allowed`, `Android Auto with a different digest is denied`, `unknown user-installed app is denied`, `user app reusing a system package name with another digest is denied`, `null caller is denied`. Run. Expected: FAIL.
- [ ] **Step 2: Implement `ControllerPolicy`.** Run. Expected: PASS.
- [ ] **Step 3: Obtain Google's Android Auto signing digest from the Pixel** (do not hand-type one): `adb shell pm path com.google.android.projection.gearhead`, pull it (`rea pull-adb-package` or `adb pull`), and run `apksigner verify --print-certs`; keep every distinct SHA-256 in a `ANDROID_AUTO_DIGESTS` constant with a comment on how it was obtained.
- [ ] **Step 4: Wire it in.** In `onConnect` build `CallerInfo` from `controller` plus `PackageManager` (`GET_SIGNING_CERTIFICATES` on API 28+; treat `NameNotFoundException` as `null`); deny with `MediaSession.ConnectionResult.reject()`. In `onGetLibraryRoot` return `LibraryResult.ofError(...)` with the permission-denied code. Log every denial at WARN as `ControllerPolicy denied pkg=<pkg> reason=<why>`. Keep `<queries>` for `com.google.android.projection.gearhead` (already declared upstream).
- [ ] **Step 5: Run the full suite, build debug, install on the Pixel, and confirm the media notification and lock-screen controls still work** (system UI is trusted); `adb logcat -d | grep "ControllerPolicy denied"` should be empty after a normal playback session.
- [ ] **Step 6: Commit.** `feat(security): only trusted, system or Android Auto callers may control the music service`.

### Task 9: Gradle supply chain: repositories, wrapper checksum, dependency verification

**Files:**
- Create: `gradle/verification-metadata.xml`, `security/test_repos_policy.py`
- Modify: `settings.gradle.kts`, `build.gradle.kts` (buildscript repositories), `gradle/wrapper/gradle-wrapper.properties`, `.gitignore`

**Interfaces:**
- Produces: `security/test_repos_policy.py` with `test_no_aliyun_mirror`, `test_no_maven_local`, `test_jitpack_is_content_filtered`, `test_wrapper_has_sha256` (text assertions over `settings.gradle.kts`, `build.gradle.kts`, `gradle-wrapper.properties`).

- [ ] **Step 1: Write the failing policy tests.** Run the Python suite. Expected: 4 FAIL.
- [ ] **Step 2: Edit the repositories.** Remove `maven.aliyun.com` everywhere; remove `mavenLocal()` from `pluginManagement` (keep the `useMavenLocalInnerTubeX` code path but only when the property is passed explicitly); restrict the generic JitPack entry with `content { includeGroup("com.github.yalantis") }` plus any other group the verification run shows genuinely comes from JitPack. Keep the existing exclusive InnerTubeX filter.
- [ ] **Step 3: Pin the wrapper.** Fetch `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256`, compare with the checksum on https://gradle.org/release-checksums/, and set `distributionSha256Sum=<hash>`.
- [ ] **Step 4: Generate verification metadata.** `sar_gradle --write-verification-metadata sha256 :app:assembleGmsRelease :app:testGmsDebugUnitTest :app:assembleGmsDebug`. Review the diff: every JitPack artifact is listed with a checksum; commit `gradle/verification-metadata.xml`.
- [ ] **Step 5: Prove it bites.** Change one `sha256` value in the metadata, run `sar_gradle :app:assembleGmsRelease`. Expected: failure mentioning "Dependency verification failed". Revert, rerun. Expected: BUILD SUCCESSFUL.
- [ ] **Step 6: Extend `.gitignore`** with `*.jks`, `*.p12`, `*.pem`, `*.key`, `keystore.properties`, `.env*`, `local.properties`.
- [ ] **Step 7: Run the Python suite and the full JVM suite.** Expected: PASS.
- [ ] **Step 8: Commit.** `chore(security): prune repositories, pin Gradle wrapper, verify dependency checksums`.

### Task 10: Release-like build on the Pixel, smoke script, white-box checks

**Files:**
- Create: `scripts/smoke.sh`, `scripts/test_smoke_helpers.py` (parsing helpers), `docs/device-checks.md`

**Interfaces:**
- Produces: `scripts/smoke.sh <apk> <package> [video_id]` exits non-zero on any failed check. Checks, in order: install (`adb install -r -d`), grant `POST_NOTIFICATIONS`, launch via `monkey`, process alive after 8 s, `adb logcat -d -b crash` empty, requested permissions from `dumpsys package` equal the allowlist, no `ALLOW_BACKUP` flag, then (only if signed in) deep-link `https://music.youtube.com/watch?v=<id>` (default `dQw4w9WgXcQ`) and poll `dumpsys media_session` for a PLAYING state from the package within 20 s. Helper `parse_playing_state(dumpsys_text: str, package: str) -> bool` is unit-tested.

- [ ] **Step 1: Write failing helper tests** (`test_playing_state_true`, `test_playing_state_other_package`, `test_playing_state_paused`) with captured `dumpsys media_session` samples; implement `parse_playing_state`; run. Expected: PASS.
- [ ] **Step 2: Write `scripts/smoke.sh`.**
- [ ] **Step 3: Build and install both variants.** Debug: `sar_gradle :app:assembleGmsDebug` and install `dev.diamondspark.saraswati.debug` (debuggable, for white-box checks). Release-like: `sar_gradle :app:assembleGmsRelease`, sign locally with `apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out /tmp/…/saraswati-local.apk` and install `dev.diamondspark.saraswati`. Note in `docs/device-checks.md`: this local signature is temporary; the CI-signed release (Phase 2) needs an uninstall first, which loses the sign-in.
- [ ] **Step 4: HUMAN CHECKPOINT.** Ask the user to open the debug variant on the Pixel and sign in with their YouTube account (their hands, their password; never ask for credentials). Then run `scripts/smoke.sh` against both variants. Expected: all checks pass, playback reaches PLAYING.
- [ ] **Step 5: White-box checks on the debug variant:** the cookie file check from Task 7 prints `0`; `adb shell am start -a com.metrolist.music.action.RECOGNITION -n dev.diamondspark.saraswati.debug/com.metrolist.music.MainActivity` opens the app normally; walk the `docs/strip-checklist.md` click-through (every Settings row, Search, Home top bar) and record results; share a playlist export and open the crash share to prove the narrowed FileProvider still works.
- [ ] **Step 6: Uninstall the debug variant** from the Pixel when done (it is debuggable). Commit `docs/device-checks.md` and the scripts: `test: device smoke script and recorded checks`.

### Task 11: Security audit

**Files:**
- Create: `docs/security-audit.md`

**Interfaces:**
- Produces: a table `ID | Area | Finding | Severity | Evidence | Disposition`, where every finding is *fixed in Task N*, *accepted with a reason*, or *deferred to a named later phase*.
- Method: one fresh reviewer per area, each given the item list and told to cite file and line. Static evidence comes from `npx -y rea-agents@6.5.0 inspect-android-package`, `search-android-classes`, `inspect-android-method` on the release APK and from reading source; source of truth for hosts is the inventory in Task 12.

- [ ] **Step 1: Run the audit over these items** (the known-risk list; add anything new): A1 cleartext policy (Task 6); A2 backup and device-transfer (Task 6); A3 FileProvider scope (Task 6); A4 exported components (Task 4); A5 music-service callers (Task 8); A6 sign-in cookie at rest (Task 7); A7 login WebView settings in `LoginScreen.kt` (JavaScript only as needed, no file/content access, no JS bridge to untrusted pages, `onReceivedSslError` must never `proceed()`); A8 `PoTokenWebView` bridge (what it exposes, what it loads); A9 `MainActivity.handleDeepLinkIntent` input handling (~1592) and the `ACTION_SEND` path; A10 backup restore (`BackupRestoreViewModel` reads fixed entry names only; check size limits and DB-restore behavior); A11 crash log content and sharing (`CrashActivity`), `Timber` planting in release; A12 InnerTubeX runtime config (JSON only, ≤4 MiB, no redirects, strict regexes before building the JS expression: `RemotePlayerConfigParser`, `RestrictedHttpFetch`, `GitHubPlayerConfigClient`, hosts `cdn.jsdelivr.net`, `github.com`, `raw.githubusercontent.com`), plus that QuickJS only evaluates YouTube's player script; A13 Listen Together protobuf parsing of untrusted server messages (feature is network-blocked in Phase 1; re-audit in Phase 4); A14 third-party libraries (note `ucrop` is unmaintained; its activity is not exported); A15 user-settable proxy (`YouTube.proxy`); A16 `PendingIntent` mutability flags (grep `PendingIntent.get` without `FLAG_IMMUTABLE`); A17 secrets in `BuildConfig` and resources (grep).
- [ ] **Step 2: Write `docs/security-audit.md`** with the table, the method, tool versions, and a "residual risk" section in plain language.
- [ ] **Step 3: Triage.** Anything rated High that no task covers becomes a new task in this plan before Task 12 continues; tell the user.
- [ ] **Step 4: Commit.** `docs: security audit of the pinned base`.

### Task 12: Network egress allowlist (deny by default)

**Files:**
- Create: `security/egress-allowlist.json`, `security/test_egress_policy.py`, `app/src/androidTest/kotlin/com/metrolist/music/saraswati/EgressPolicyInstrumentedTest.kt`
- Modify: `app/src/main/res/xml/network_security_config.xml`, `app/build.gradle.kts` (androidTest dependencies `androidx.test:runner`, `androidx.test.ext:junit`), `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`

**Interfaces:**
- Produces: `security/egress-allowlist.json` = `{"allowed": [{"name": "<domain>", "subdomains": true|false}, ...], "forbidden_hosts": [...]}`. Initial candidates (confirmed or trimmed by the evidence in Steps 3-4): with `subdomains: true` → `youtube.com`, `googlevideo.com`, `ytimg.com`, `ggpht.com`, `googleusercontent.com`, `gstatic.com`, `google.com`, `lrclib.net`, `boidu.dev`, `githubusercontent.com`; with `subdomains: false` → `github.com` (so `api.github.com` stays blocked), `cdn.jsdelivr.net`, and, only if Step 3 shows they are needed, the exact hosts `jnn-pa.googleapis.com` and `www.googleapis.com` (never the whole `googleapis.com`, which would also cover the Gemini AI host). `forbidden_hosts` (each must resolve to *blocked*, and the test asserts it against the final XML, including that a parent domain does not cover it): `discord.com`, `cdn.discordapp.com`, `openrouter.ai`, `api.openai.com`, `api.anthropic.com`, `api.mistral.ai`, `api.x.ai`, `api.perplexity.ai`, `api.inceptionlabs.ai`, `generativelanguage.googleapis.com`, `api.deepl.com`, `api-free.deepl.com`, `ws.audioscrobbler.com`, `amp.shazam.com`, `music.apple.com`, `amp-api.music.apple.com`, `metroserverx.meowery.eu`, `jewishmusic.fm`, `lyrics.kugou.com`, `mobileservice.kugou.com`, `search.zemer.io`, `lyrics.paxsenix.org`, `returnyoutubedislikeapi.com`, `api.github.com`.
- The network config shape: `<base-config cleartextTrafficPermitted="false"><trust-anchors/></base-config>` (trust nothing by default) plus one `<domain-config>` per allowed domain with system trust anchors. `security/test_egress_policy.py` asserts: base config trusts nothing, cleartext is false everywhere, the XML's domain set (with each `includeSubdomains`) equals `egress-allowlist.json`, and no forbidden host is covered by any allowed entry (a host is covered by `x.com` with subdomains if it equals `x.com` or ends with `.x.com`; by `x.com` without subdomains only if it equals `x.com`). Test names: `base config trusts nothing`, `cleartext is off everywhere`, `xml matches the allowlist file`, `no forbidden host is covered`.

- [ ] **Step 1: Prove the mechanism first (spike).** Write `EgressPolicyInstrumentedTest`: `allowed host connects` (`HttpsURLConnection` to `https://music.youtube.com/` returns a response code), `blocked host fails the TLS handshake` (`https://example.com/` throws `SSLHandshakeException`), `blocked listen-together host fails` (`https://metroserverx.meowery.eu/`). Apply the XML. Run `sar_gradle :app:connectedGmsDebugAndroidTest --tests '*EgressPolicyInstrumentedTest'` with the Pixel attached. Expected: PASS. **If an empty `<trust-anchors/>` is rejected or does not block**, stop and use the fallback: a shared `EgressInterceptor` (host allowlist from the same JSON) added at every OkHttp creation site (`listentogether/ListenTogetherClient.kt:617`, `utils/potoken/PoTokenWebView.kt:457`, `eq/data/GitHubAutoEqSearch.kt:35`, `viewmodels/BackupRestoreViewModel.kt:445`, `playback/DownloadUtil.kt:80`, `playback/MusicService.kt:3474`) and the Ktor/Coil clients; record the choice in `docs/security-audit.md`.
- [ ] **Step 2: Write `security/test_egress_policy.py`; run.** Expected: PASS after the XML is written.
- [ ] **Step 3: Collect dynamic evidence.** On the debug build, with `adb logcat -c` first, exercise: sign-in (the Google WebView), Home, search, play three songs (one Hindi), open lyrics (LrcLib and BetterLyrics sources), images in Library, download a song, start a Cast session, open Listen Together. Run `adb logcat -d | grep -E "SSLHandshakeException|CertPathValidatorException|Trust anchor"` and list each failing host.
- [ ] **Step 4: Decide each failing host.** Needed for a working feature → add its registrable domain to the allowlist JSON and XML with a one-line reason in `docs/security-audit.md`; otherwise leave blocked. Also check that WebView sign-in completes (if WebView ignores the app policy, note it; if it honors it and breaks, add the Google domains that fail).
- [ ] **Step 5: Verify features on the Pixel** with `scripts/smoke.sh` and a screenshot check (`npx -y rea-agents@6.5.0 capture-adb-screen`): Home art visible, lyrics shown for a known song, sign-in persists after force-stop.
- [ ] **Step 6: Run all suites.** Expected: PASS.
- [ ] **Step 7: Commit.** `feat(security): deny-by-default egress allowlist with instrumented proof`.

### Task 13: Performance and Android Auto baselines

**Files:**
- Create: `scripts/perf_stats.py`, `scripts/test_perf_stats.py`, `scripts/perf-baseline.sh`, `docs/perf-baseline.md`, `docs/android-auto-baseline.md`

**Interfaces:**
- Produces: `def parse_am_start_total_time(out: str) -> int`, `def parse_gfxinfo_janky_percent(out: str) -> float`, `def median(values: list[float]) -> float`, `def p90(values: list[float]) -> float`; `scripts/perf-baseline.sh <package> <runs>` prints median and p90 for cold start (`am start -W` `TotalTime`), time from deep link to PLAYING (`parse_playing_state` from Task 10), and janky-frame percent after 10 scripted swipes on Home (`dumpsys gfxinfo <package> reset` then `framestats`).

- [ ] **Step 1: Write failing tests** for the four parsers with captured samples (`TotalTime: 545` → 545; a gfxinfo block with `Janky frames: 12 (4.80%)` → 4.8; `median([3,1,2]) == 2`; `p90` of 1..10 → 9). Run. Expected: FAIL; implement; PASS.
- [ ] **Step 2: Write `perf-baseline.sh`** (screen on, battery >50%, same Wi-Fi, 10 runs, discard the first, both packages signed in).
- [ ] **Step 3: Measure** Metrolist `com.metrolist.music` 13.6.3 and `dev.diamondspark.saraswati` on the Pixel; write `docs/perf-baseline.md` with the raw numbers and the budgets from the spec (cold start ≥20% faster than Metrolist and ≤1.0 s; tap-to-audio p50 ≤1.0 s uncached; jank <1%). State plainly which budgets are not yet met; Phase 2's Macrobenchmark and Baseline Profile work is what closes them, not this task.
- [ ] **Step 4: Android Auto baseline (user at the computer).** Enable Android Auto developer mode and "Unknown sources" on the Pixel, `adb forward tcp:5277 tcp:5277`, start the head unit server, run `~/Android/Sdk/extras/google/auto/desktop-head-unit`. Record in `docs/android-auto-baseline.md` for Saraswati: app listed, root browse tree loads, play a song, next/previous, voice search through the DHU, resume after reconnect, and `adb logcat -d | grep "ControllerPolicy denied"` shows **no Android Auto denial**. Also run Metrolist through the same checklist for comparison.
- [ ] **Step 5: Commit.** `docs: performance and Android Auto baselines, measurement scripts`.

### Task 14: Wrap-up: full gates, whole-branch review, merge

**Files:**
- Modify: `README.md`, `SECURITY.md`, `docs/build-baseline.md` (final numbers)

- [ ] **Step 1: Run every gate from a clean tree.** `git clean -xfd` is not allowed on the user's files; instead `git status --short` must be empty, then: Python suites (`security`, `scripts`), `sar_gradle clean :app:testGmsDebugUnitTest :app:assembleGmsRelease`, `python3 -I security/apk_gate.py check --apk app/build/outputs/apk/gms/release/app-gms-release-unsigned.apk --allowlist security/manifest-allowlist.json`. Expected: all pass, exit 0.
- [ ] **Step 2: Record per-phone readiness data** in `docs/device-checks.md`: for each family phone (when connected), `ro.product.cpu.abilist`, Android version, brand. A missing arm64 means adding `armeabi-v7a` before rollout.
- [ ] **Step 3: Whole-branch review.** Dispatch one fresh reviewer on the most capable model with the diff `main..phase1-harden`, `docs/security-audit.md` and this plan; it checks every Review Focus item has its test, no secret is committed (`git log -p | grep -iE 'sapisid=|api[_-]?key|password'`), and every upstream-file edit has a `SARASWATI:` marker. Fix findings.
- [ ] **Step 4: Merge.** Push `phase1-harden`, open a PR to `main` with the audit summary, and merge it (no CI exists yet; Phase 2 adds it).
- [ ] **Step 5: Report to the user in plain words:** what changed, what is blocked on purpose (lyrics providers, trackers, AI keys), what is still open (Listen Together UI present but disconnected), the baseline numbers, and the decisions to veto (lyrics providers limited to YouTube + LrcLib + BetterLyrics; AutoEQ online search disabled; Last.fm, Discord, song recognition and alarms removed).

## Self-review notes

- Spec coverage: fork, rebrand, base pin (Tasks 1-3); strip permissions and features (4, 5); cleartext, backup and FileProvider (6); cookie encryption and restore safety (7); controller allowlist (8); supply chain (9); audit (11); host allowlist (12); baselines including Android Auto (13). Phase 2 owns signing, CI, updater, sync bot, Sentry, Baseline Profiles and developer verification.
- Deliberate deviations from the design text, to veto: `allowBackup=false` instead of only excluding the cookie; Keystore sealing instead of Tink (no extra dependency); host allowlist enforced by Android's network security config instead of a per-client OkHttp interceptor (with the interceptor as the tested fallback); no mitmproxy (no user CA ever gets installed on a phone with banking apps; hosts are found statically plus from TLS failures).

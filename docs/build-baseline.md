# Build baseline

The pinned upstream base, built and unit-tested on the Saraswati dev machine before any Phase 1 change. Later tasks compare their results with this page.

## Result

Recorded 2026-10-17.

| Item | Value |
| --- | --- |
| Tree | `927e2b57ca5efb6aafbcf5b7f430d06e0d9778df` on branch `phase1-harden`: upstream Metrolist `76fa5a78b5110674442884b6f21c0cef51cdb782` (2026-10-08) with upstream CI removed and project docs added. The app sources are unchanged. |
| Command | `source scripts/dev-env.sh && sar_ensure_debug_keystore && sar_gradle :app:assembleGmsDebug :app:testGmsDebugUnitTest --rerun-tasks` |
| Outcome | `BUILD SUCCESSFUL`, no task restored from the build cache |
| Wall time | 4m 27s as reported by Gradle, 268 s (4m 28s) on the clock including the cold daemon start. 84 tasks executed, 1 up-to-date, warm `~/.gradle`. |
| Unit tests | 186 tests in 41 classes: 0 failures, 0 errors, 0 skipped |
| Known upstream failures | None |
| Debug APK | `app/build/outputs/apk/gms/debug/app-gms-debug.apk`, 53,617,609 bytes (51.1 MiB). Universal: carries arm64-v8a, armeabi-v7a, x86 and x86_64 native libraries. Signed with the debug key (`CN=Android Debug`). |
| Toolchain | Eclipse Temurin JDK 21.0.12.1, Android SDK platform 37, build-tools 37.0.0, platform-tools 37.0.1, Gradle 9.7.1 (from the wrapper) |

`--rerun-tasks` is part of the recorded command on purpose. `gradle.properties` turns the Gradle build cache on, and on a machine that already built this commit the plain command restores Kotlin compilation and the unit-test task from that cache instead of running them. The first, plain run of this baseline took 1m 44s for that reason: 47 of 85 tasks came from the cache, the test task included. Add `--rerun-tasks` whenever you need a real compile-and-test run.

## Build on a fresh machine

1. Install Eclipse Temurin JDK 21 so that `$HOME/tools/jdk-21/bin/java` exists, or `export JAVA_HOME=...` to another JDK 21 before step 3.
2. Install the Android command-line tools into `$HOME/Android/Sdk/cmdline-tools/latest`, or into `$ANDROID_HOME` if you export that first.
3. From the repo root, load the environment and install the SDK packages. `dev-env.sh` warns about missing SDK pieces until the install has finished; that is expected.

```bash
source scripts/dev-env.sh
sdkmanager --licenses
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
source scripts/dev-env.sh   # prints nothing when the toolchain is complete
```

4. Check the toolchain. Expect `openjdk version "21.`, an adb `Version 37.`, and an apksigner version with no `java: not found`.

```bash
java -version 2>&1 | head -1
adb version | sed -n 2p
apksigner --version
```

5. Create the debug keystore, build, and free the daemons' memory:

```bash
sar_ensure_debug_keystore
sar_gradle :app:assembleGmsDebug :app:testGmsDebugUnitTest
./gradlew --stop
```

`sar_gradle` is the only supported way to build here. It caps the Gradle heap at 3 GB and the Kotlin daemon at 2 GB and runs under `nice -n 10`, because the dev machine has about 8 GB of RAM to spare. Run it from the repo root, one build at a time, and do not start the Android emulator (about 4 GB) while a build is running.

The time above was measured with a warm `~/.gradle` (about 1.8 GB: wrapper distribution, dependencies and build cache, left by an earlier build of this commit). On a machine with an empty one, the first build also downloads the wrapper and every dependency, so it takes longer.

## Test totals

After the unit-test task, from the repo root:

```bash
python3 -I -c "import glob,xml.etree.ElementTree as E;r=[E.parse(p).getroot() for p in glob.glob('app/build/test-results/testGmsDebugUnitTest/*.xml')];print(*(sum(int(x.get(k)) for x in r) for k in ('tests','failures','errors','skipped')))"
```

It prints `tests failures errors skipped`. Baseline: `186 0 0 0`.

## Keystore pitfall

Upstream hard-wires `$HOME/.android/debug.keystore` as the debug signing key (`signingConfigs.debug` in `app/build.gradle.kts`). On a machine without that file the build fails at `:app:validateSigningGmsDebug` with a `Keystore file ... not found` error. Run `sar_ensure_debug_keystore` first: it creates the keystore with the standard debug values (alias `androiddebugkey`, store and key pass `android`, 2048-bit RSA, 10000 days) at mode 600, and it never touches an existing file.

Two other settings take precedence over that file, in this order: the `METROLIST_DEBUG_KEYSTORE_PATH` environment variable, then a gitignored `app/persistent-debug.keystore`. Whichever key signs the build is the app's signing identity: a phone that has a debug build signed by one key refuses an update signed by another (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so keep the keystore instead of regenerating it. The store and key pass are the public Android debug defaults, but the file itself is what identifies your debug builds, so it stays outside the repo and is never committed.

## Manifest overlay and the APK gate (Task 4)

`app/src/gms/AndroidManifest.xml` removes the mic/alarm/boot permissions and the recognizer, Discord-OAuth, alarm and home-screen-widget components upstream ships, plus the unused `org.apache.http.legacy` library. `security/apk_gate.py check --apk <apk> --allowlist security/manifest-allowlist.json` enforces the result; it passed (exit 0) against `app/build/outputs/apk/gms/release/app-gms-release-unsigned.apk` built from this commit.

Snapshot of that APK's merged manifest (`apk_gate.py snapshot`):

| Item | Value |
| --- | --- |
| Package | `dev.diamondspark.saraswati` |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`, `READ_MEDIA_AUDIO`, `dev.diamondspark.saraswati.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| Exported components | `activity:com.metrolist.music.MainActivity`, `activity-alias:com.metrolist.music.MainActivityAlias`, `activity-alias:com.metrolist.music.MainActivityStatic`, `service:com.metrolist.music.playback.MusicService`, `receiver:androidx.media3.session.MediaButtonReceiver`, `receiver:androidx.profileinstaller.ProfileInstallReceiver` |
| allowBackup / debuggable | `false` / absent (not `true`) |

Line-by-line justification for the entries in `security/manifest-allowlist.json` that are not self-evident from the Global Constraints' permission list:

- `dev.diamondspark.saraswati.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — synthesized by AGP/the Play Core / Media3 toolchain for app-signing-scoped dynamic broadcast receivers (`Context.registerReceiver` with `RECEIVER_NOT_EXPORTED` on API levels that back-fill the flag); it is package-scoped (`${applicationId}...`) and cannot be held by another app, so it carries no cross-app risk.
- `android.permission.READ_MEDIA_AUDIO` (Phase 5, owner-approved) — lets Saraswati list and play the audio files already on the phone (Android 13+ only; older phones are told so). It is a runtime permission, asked for only after an in-app explanation that the files never leave the phone, and settings has a Local files switch that turns every scan and read off. It reads the MediaStore audio table only. `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` are forbidden: they would also reach photos and videos. `ShippedPermissionPinTests` fails if the shipped allowlist gains any other permission.
- `receiver:androidx.media3.session.MediaButtonReceiver` exported `true` — required so the system (lock-screen controls, Bluetooth/Android Auto head units, the notification's media-button intent) can deliver `ACTION_MEDIA_BUTTON`; this is how every Media3 app wires hardware/BT playback buttons and is the documented, intended use of the component.
- `receiver:androidx.profileinstaller.ProfileInstallReceiver` exported `true` — added by the `androidx.profileinstaller` baseline-profile library; it is exported but gated behind `android.permission.DUMP`, a signature/system-only permission no third-party app holds, so it cannot be triggered by another app on a non-rooted device. Kept exported per upstream/AndroidX default rather than patched, since `DUMP` already closes it off.
- `activity-alias:com.metrolist.music.MainActivityStatic` exported `true`, `enabled="false"` — a disabled static-icon alias upstream ships for icon-pack theming; disabled aliases cannot be launched regardless of `exported`, so it is inert but still enumerates as an exported component in the manifest and is allowlisted for that reason.
- `forbidden_permissions` beyond the four literal ones Task 4's brief names (`RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`) spell out the Global Constraints' "Never present" categories as concrete permission constants, since the gate matches exact strings: `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES` (named literally); SMS → `SEND_SMS`/`RECEIVE_SMS`/`READ_SMS`/`RECEIVE_MMS`/`RECEIVE_WAP_PUSH`; contacts → `READ_CONTACTS`/`WRITE_CONTACTS`/`GET_ACCOUNTS`; location → `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`/`ACCESS_BACKGROUND_LOCATION`; phone state → `READ_PHONE_STATE`/`READ_PHONE_NUMBERS`/`CALL_PHONE`/`ANSWER_PHONE_CALLS`/`PROCESS_OUTGOING_CALLS`; camera → `CAMERA`; accessibility → `BIND_ACCESSIBILITY_SERVICE`; notification-listener → `BIND_NOTIFICATION_LISTENER_SERVICE`; overlay → `SYSTEM_ALERT_WINDOW`.

No Cast, Play Services or Google Sign-In permission or exported component appeared in this snapshot (the `gms` flavor only adds the Cast `OPTIONS_PROVIDER_CLASS_NAME` `<meta-data>`, which is not a permission or an exported component), so none needed a separate line here.

### Intent filters, queries and resource references (fix wave A)

Both allowlists also pin, exactly, every component's intent filters (actions, categories, `<data>` scheme/host/port/path/pathPrefix/pathPattern/mimeType and `autoVerify`), the `<queries>` block, and the `dataExtractionRules`, `fullBackupContent` and `networkSecurityConfig` references of `<application>`. A deep link that widens (`android:path="/pair"` to a `pathPrefix`), a re-added filter (the metrolist.cc Listen Together link), a new `<queries>` package or an exported component without `android:exported` all fail `apk_gate.py check`, as does a pinned filter that disappears. `<data>` elements are compared as one set of values per attribute, the way Android combines them, so how a manifest spreads them over elements does not matter.

The allowlist for the app also carries a `hub_host_agreement` section. `check` then requires that the host of `HUB_BASE_URL` for the gms flavor in `app/build.gradle.kts`, the host of the pairing-link filter (`/pair`) in the merged manifest, an exact (`includeSubdomains="false"`) `<domain>` for it in the APK's own network security config, and an exact entry in `security/egress-allowlist.json` are all the same host.

To change a pin on purpose, build the release APK, run `python3 -I security/apk_gate.py snapshot --apk <apk>`, copy `intent_filters`, `queries`, `references` and `reference_ids` into the allowlist and review the diff.

#### References are pinned by name (fix wave B1)

A built APK's manifest holds the three references as raw resource ids (`@ref/0x7f150006`), and an id shifts whenever any xml resource is added or removed, so pinning ids made the gate fail on unrelated resource changes. `references` is now pinned by resource name (`@xml/network_security_config`): `check --apk` and `snapshot --apk` resolve each id through the APK's own resource table with `aapt2 dump resources`. aapt2 is found under `build-tools/*/aapt2` of `$ANDROID_HOME`, then `$ANDROID_SDK_ROOT` (newest build-tools first), then on `PATH`; the GitHub runners set `ANDROID_HOME`, so the CI and release gate steps need nothing extra. `--xml` (a source manifest) already names its resources and never needs aapt2.

`reference_ids` keeps the raw ids as a fallback pin. If aapt2 cannot be found or fails, the gate prints a warning on stderr and compares the raw ids with `reference_ids` instead: it exits 0 if they are unchanged and 1 only if an id differs (which an unrelated resource change can cause; re-snapshot with aapt2 available). `snapshot` without aapt2 leaves `references` out of its output rather than filling it with ids.

## Final Phase 1 test count (after the whole-branch review fix round)

The "Baseline" section above is deliberately untouched: it is the pinned
upstream measurement from before any Phase 1 change, kept as the fixed
comparison point. This section records where the number ended up after
Tasks 1–17 and the final whole-branch review's fix round (C1/I1/I2/I4/I5 and
the smaller related fixes).

| Item | Value |
| --- | --- |
| Command | `source scripts/dev-env.sh && rm -rf app/build/test-results/testGmsDebugUnitTest && sar_gradle :app:testGmsDebugUnitTest` |
| Unit tests | `288 0 0 0` (tests, failures, errors, skipped — same one-liner as "Test totals" below) |
| `security/apk_gate.py check` | exit 0 against a fresh `:app:assembleGmsRelease` output |
| Python gates | `python3 -I -m unittest discover -s security -p 'test_*.py'` and the same with `-s scripts`: 16 and 17 tests, 0 failures |

Task 14's own clean-build run (step 1, before this fix round) measured 285
tests and recorded a build-environment finding worth keeping visible here:
**the default heap in `scripts/dev-env.sh` (Gradle 3 GB / Kotlin daemon
2 GB) OOM'd twice on a genuinely clean, full-module Kotlin compile** (large
Compose files such as `CsvColumnMappingDialog.kt` hit the ASM bytecode
analyzer's GC overhead limit). It only succeeded after a one-off larger
heap:

```bash
sar_gradle clean :app:testGmsDebugUnitTest :app:assembleGmsRelease \
  "-Dorg.gradle.jvmargs=-Xmx6g" "-Dkotlin.daemon.jvm.options=-Xmx4g"
```

This never affected any task's normal, incremental build during execution —
only a true `clean` (no cached Kotlin/compile outputs at all) triggers it.
Do not edit the committed `dev-env.sh` default to work around this; pass the
larger heap one-off when a clean build is actually needed. Flagged for Phase
2 Task 3 (the CI workflow): a GitHub Actions runner's first build is always
clean, so it will need the same heap bump (or K2 incremental-compilation
avoidance) to not OOM on its first run.

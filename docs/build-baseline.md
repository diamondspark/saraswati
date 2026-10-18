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

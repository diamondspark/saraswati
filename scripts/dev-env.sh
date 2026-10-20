# shellcheck shell=bash
# Saraswati build environment. Source it from the repo root; do not run it:
#
#   source scripts/dev-env.sh
#   sar_ensure_debug_keystore
#   sar_gradle :app:assembleGmsDebug :app:testGmsDebugUnitTest
#
# Every location defaults to a $HOME-relative path and is overridden by
# exporting the variable before sourcing. Sourcing twice leaves PATH unchanged.
# Keep this file safe to source into any shell: it must not change shell
# options or end the calling shell.

if [ "${BASH_SOURCE[0]:-$0}" = "$0" ]; then
  echo "dev-env.sh is meant to be sourced:  source scripts/dev-env.sh" >&2
fi

export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export REA_JADX_MCP_JAR="${REA_JADX_MCP_JAR:-$HOME/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar}"
export REA_ADB_PATH="${REA_ADB_PATH:-$ANDROID_HOME/platform-tools/adb}"

# Put $1 first on PATH, dropping any copy already there so a repeat is a no-op
# and the SDK tools win over system ones such as /usr/bin/adb.
_sar_path_front() {
  local dir="$1" rest=":$PATH:"
  while [[ "$rest" == *":$dir:"* ]]; do
    rest="${rest/":$dir:"/:}"
  done
  rest="${rest#:}"
  rest="${rest%:}"
  PATH="$dir${rest:+:$rest}"
}

# Prepended in reverse so the final order is: JDK, platform-tools,
# cmdline-tools, build-tools, then the original PATH.
_sar_path_front "$ANDROID_HOME/build-tools/37.0.0"
_sar_path_front "$ANDROID_HOME/cmdline-tools/latest/bin"
_sar_path_front "$ANDROID_HOME/platform-tools"
_sar_path_front "$JAVA_HOME/bin"
export PATH

# Gradle heap 4 GB, Kotlin daemon 4 GB, low CPU priority: the dev machine has
# about 8 GB of RAM to spare, so always build through this wrapper.
sar_gradle() {
  if [ ! -x ./gradlew ]; then
    echo "sar_gradle: no executable ./gradlew in $PWD; run it from the repo root" >&2
    return 1
  fi
  nice -n 10 ./gradlew --console=plain "-Dorg.gradle.jvmargs=-Xmx4g -Dkotlin.daemon.jvm.options=-Xmx4g -XX:+UseParallelGC" "$@"
}

# Upstream hard-wires ~/.android/debug.keystore for debug signing, so a fresh
# machine fails at :app:validateSigningGmsDebug until this file exists.
sar_ensure_debug_keystore() {
  local ks="$HOME/.android/debug.keystore"
  if [ -f "$ks" ]; then
    return 0
  fi
  mkdir -p "$HOME/.android" || return 1
  (
    umask 077
    keytool -genkeypair -keystore "$ks" -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
  ) || return 1
  chmod 600 "$ks"
}

# Fail fast with a one-line remedy instead of a Gradle stack trace later.
_sar_check_toolchain() {
  local rc=0 p
  if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "dev-env.sh: no java at $JAVA_HOME/bin/java; install JDK 21 (Temurin) there or set JAVA_HOME" >&2
    rc=1
  fi
  for p in platform-tools/adb build-tools/37.0.0/apksigner; do
    if [ ! -e "$ANDROID_HOME/$p" ]; then
      echo "dev-env.sh: missing $ANDROID_HOME/$p; install the Android SDK packages from docs/build-baseline.md or set ANDROID_HOME" >&2
      rc=1
    fi
  done
  return "$rc"
}

_sar_check_toolchain

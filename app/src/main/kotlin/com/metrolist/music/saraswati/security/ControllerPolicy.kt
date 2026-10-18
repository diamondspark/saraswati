/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: upstream's MediaLibrarySessionCallback.onConnect() accepted
// every controller that bound to the session (audit NEW-4) — any app on
// the device could control playback, like songs or browse the library
// through the exported MediaLibraryService. ControllerPolicy.isAllowed()
// decides, in a fixed order, who may control this service: our own
// package; any controller Media3/the platform already marked trusted
// (system UI, Bluetooth, the Assistant); Android Auto, but only by
// verified signing certificate, never by package name alone (see
// CallerInfoFactory for why the package name must be verified against
// the connecting uid first); or any other system app. Everyone else is
// denied.
package com.metrolist.music.saraswati.security

/** What is known about a controller after [CallerInfoFactory] has verified its claimed package name. */
data class CallerInfo(
    val packageName: String,
    val isTrusted: Boolean,
    val isSystemApp: Boolean,
    val signingSha256: Set<String>,
)

object ControllerPolicy {
    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

    // Harvested from the Pixel 8 Pro on 2026-10-17: Android Auto
    // (com.google.android.projection.gearhead) 17.8.663874-release, the
    // v3 APK signing certificate's SHA-256 — i.e. the app signer itself.
    // The Play source-stamp digest apksigner also prints for this APK
    // (3257d599...) is a *different* value and is NOT the app signer;
    // using it here would accept the wrong certificate. Re-verify this
    // value against the live phone whenever one is attached again
    // (`adb -s <serial> shell pm path com.google.android.projection.gearhead`
    // + `apksigner verify --print-certs`); see constraints.md R7 — that
    // re-verification is DEFERRED, this harvested value is used as-is.
    private const val ANDROID_AUTO_SIGNING_DIGEST =
        "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295"

    /** Passed as `expectedDigests` to [isAllowed] for real callers; tests supply their own map. */
    val ANDROID_AUTO_DIGESTS: Map<String, Set<String>> =
        mapOf(ANDROID_AUTO_PACKAGE to setOf(ANDROID_AUTO_SIGNING_DIGEST))

    /**
     * Rules, checked in this exact order — the first match decides:
     *  1. [caller] is null (its package name could not be verified against its uid) -> deny.
     *  2. [caller]'s package is [selfPackage] -> allow.
     *  3. [caller] is already trusted -> allow.
     *  4. [caller]'s package is a key of [expectedDigests] -> allow only if
     *     [CallerInfo.signingSha256] intersects that package's expected digests; otherwise deny,
     *     even if the caller also looks like a system app. A package name that happens to match an
     *     expected-digest entry is held to that digest, full stop — it never falls through to the
     *     isSystemApp rule below.
     *  5. [caller] is a system app -> allow.
     *  6. otherwise -> deny.
     */
    fun isAllowed(
        selfPackage: String,
        caller: CallerInfo?,
        expectedDigests: Map<String, Set<String>>,
    ): Boolean {
        if (caller == null) return false
        if (caller.packageName == selfPackage) return true
        if (caller.isTrusted) return true
        val expected = expectedDigests[caller.packageName]
        if (expected != null) return caller.signingSha256.any { it in expected }
        if (caller.isSystemApp) return true
        return false
    }
}

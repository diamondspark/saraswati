/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the controller allow/deny policy (audit
// NEW-4) — only our own package, an already-trusted controller, Android
// Auto by verified signing certificate, or another system app may control
// this service.
package com.metrolist.music.saraswati.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerPolicyTest {
    private val selfPackage = "dev.diamondspark.saraswati"
    private val androidAutoDigests = mapOf(ControllerPolicy.ANDROID_AUTO_PACKAGE to setOf("expected-digest"))

    @Test
    fun `own package is allowed`() {
        val caller = CallerInfo(selfPackage, isTrusted = false, isSystemApp = false, signingSha256 = emptySet())

        assertTrue(ControllerPolicy.isAllowed(selfPackage, caller, androidAutoDigests))
    }

    @Test
    fun `trusted controller is allowed`() {
        val caller =
            CallerInfo("com.android.bluetooth", isTrusted = true, isSystemApp = false, signingSha256 = emptySet())

        assertTrue(ControllerPolicy.isAllowed(selfPackage, caller, androidAutoDigests))
    }

    @Test
    fun `system app is allowed`() {
        val caller =
            CallerInfo("com.android.systemui", isTrusted = false, isSystemApp = true, signingSha256 = emptySet())

        assertTrue(ControllerPolicy.isAllowed(selfPackage, caller, androidAutoDigests))
    }

    @Test
    fun `Android Auto with the expected digest is allowed`() {
        val caller =
            CallerInfo(
                ControllerPolicy.ANDROID_AUTO_PACKAGE,
                isTrusted = false,
                isSystemApp = false,
                signingSha256 = setOf("expected-digest"),
            )

        assertTrue(ControllerPolicy.isAllowed(selfPackage, caller, androidAutoDigests))
    }

    @Test
    fun `Android Auto with a different digest is denied`() {
        val caller =
            CallerInfo(
                ControllerPolicy.ANDROID_AUTO_PACKAGE,
                isTrusted = false,
                isSystemApp = false,
                signingSha256 = setOf("some-other-digest"),
            )

        assertFalse(ControllerPolicy.isAllowed(selfPackage, caller, androidAutoDigests))
    }

    @Test
    fun `unknown user-installed app is denied`() {
        val caller =
            CallerInfo("com.evil.clone", isTrusted = false, isSystemApp = false, signingSha256 = emptySet())

        assertFalse(ControllerPolicy.isAllowed(selfPackage, caller, androidAutoDigests))
    }

    @Test
    fun `user app reusing a system package name with another digest is denied`() {
        // Even a CallerInfo that claims the Android Auto package name and is also flagged as a
        // system app must still be denied on a digest mismatch: matching a key of
        // expectedDigests is decided by the digest alone, it never falls through to the
        // isSystemApp rule.
        val caller =
            CallerInfo(
                ControllerPolicy.ANDROID_AUTO_PACKAGE,
                isTrusted = false,
                isSystemApp = true,
                signingSha256 = setOf("attacker-digest"),
            )

        assertFalse(ControllerPolicy.isAllowed(selfPackage, caller, androidAutoDigests))
    }

    @Test
    fun `null caller is denied`() {
        assertFalse(ControllerPolicy.isAllowed(selfPackage, null, androidAutoDigests))
    }

    // SARASWATI: final fix wave (M7) — the Sangat button is shown to the real Android Auto only.
    @Test
    fun `only Android Auto with its expected digest is the car`() {
        fun caller(
            pkg: String,
            digest: String,
            system: Boolean = false,
        ) = CallerInfo(pkg, isTrusted = true, isSystemApp = system, signingSha256 = setOf(digest))

        assertTrue(ControllerPolicy.isAndroidAuto(caller(ControllerPolicy.ANDROID_AUTO_PACKAGE, "expected-digest"), androidAutoDigests))
        assertFalse("wrong certificate", ControllerPolicy.isAndroidAuto(caller(ControllerPolicy.ANDROID_AUTO_PACKAGE, "other"), androidAutoDigests))
        assertFalse("the system UI is trusted but not the car", ControllerPolicy.isAndroidAuto(caller("com.android.systemui", "expected-digest", true), androidAutoDigests))
        assertFalse("our own app", ControllerPolicy.isAndroidAuto(caller(selfPackage, "expected-digest"), androidAutoDigests))
        assertFalse("unverified", ControllerPolicy.isAndroidAuto(null, androidAutoDigests))
        assertFalse("no digest on file", ControllerPolicy.isAndroidAuto(caller(ControllerPolicy.ANDROID_AUTO_PACKAGE, "expected-digest"), emptyMap()))
    }
}

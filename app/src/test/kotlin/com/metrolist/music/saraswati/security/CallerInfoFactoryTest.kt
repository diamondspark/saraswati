/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the uid-ownership check (audit NEW-4).
// A controller's claimed package name must actually belong to its uid
// before ControllerPolicy ever sees it, or a spoofed package name
// claiming to be Android Auto would sail through the digest allowlist.
package com.metrolist.music.saraswati.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakePackageLookup(
    private val uidPackages: Map<Int, List<String>> = emptyMap(),
    private val digests: Map<String, Set<String>> = emptyMap(),
    private val systemApps: Set<String> = emptySet(),
) : PackageLookup {
    override fun packagesForUid(uid: Int): List<String> = uidPackages[uid].orEmpty()

    override fun signingSha256(
        uid: Int,
        packageName: String,
    ): Set<String> = digests[packageName].orEmpty()

    override fun isSystemApp(packageName: String): Boolean = packageName in systemApps
}

class CallerInfoFactoryTest {
    @Test
    fun `a package that does not belong to the controller's uid yields null`() {
        // uid 1000 really owns com.real.owner; it claims to be Android Auto instead.
        val lookup = FakePackageLookup(uidPackages = mapOf(1000 to listOf("com.real.owner")))

        val result =
            CallerInfoFactory.create(
                controllerPackage = ControllerPolicy.ANDROID_AUTO_PACKAGE,
                controllerUid = 1000,
                isTrusted = false,
                lookup = lookup,
            )

        assertNull(result)
    }

    @Test
    fun `a package owned by the uid yields its digests and system flag`() {
        val lookup =
            FakePackageLookup(
                uidPackages = mapOf(2000 to listOf(ControllerPolicy.ANDROID_AUTO_PACKAGE)),
                digests = mapOf(ControllerPolicy.ANDROID_AUTO_PACKAGE to setOf("abc123")),
                systemApps = setOf(ControllerPolicy.ANDROID_AUTO_PACKAGE),
            )

        val result =
            CallerInfoFactory.create(
                controllerPackage = ControllerPolicy.ANDROID_AUTO_PACKAGE,
                controllerUid = 2000,
                isTrusted = false,
                lookup = lookup,
            )

        assertEquals(
            CallerInfo(
                packageName = ControllerPolicy.ANDROID_AUTO_PACKAGE,
                isTrusted = false,
                isSystemApp = true,
                signingSha256 = setOf("abc123"),
            ),
            result,
        )
    }
}

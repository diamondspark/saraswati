/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: new test asserting the fork's identity (applicationId, arm64-only
// ABI, flavor defaults and versionCode scheme) never silently regresses back
// to Metrolist's.
package com.metrolist.music.saraswati

import com.metrolist.music.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

class BuildIdentityTest {
    @Test
    fun `applicationId is Saraswati's debug id, not Metrolist's`() {
        assertEquals("dev.diamondspark.saraswati.debug", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `shipped ABI is arm64 only`() {
        assertEquals("arm64", BuildConfig.ARCHITECTURE)
    }

    @Test
    fun `gms flavor ships with no in-app updater`() {
        assertFalse(BuildConfig.UPDATER_AVAILABLE)
    }

    @Test
    fun `gms flavor keeps Cast support`() {
        assertTrue(BuildConfig.CAST_AVAILABLE)
    }

    @Test
    fun `versionCode is git-derived, past upstream's last manual bump`() {
        // Upstream's last hand-bumped versionCode was 153. Saraswati's is the
        // repo's real commit count (thousands), so this also catches a
        // shallow-clone regression, which would produce a tiny versionCode.
        assertTrue(BuildConfig.VERSION_CODE > 153)
    }

    @Test
    fun `release shortcuts target Saraswati's own release package`() {
        assertTargetPackages(File("src/main/res/xml/shortcuts.xml"), "dev.diamondspark.saraswati")
    }

    @Test
    fun `debug shortcuts target Saraswati's own debug package`() {
        assertTargetPackages(File("src/debug/res/xml/shortcuts.xml"), "dev.diamondspark.saraswati.debug")
    }

    private fun assertTargetPackages(file: File, expectedPackage: String) {
        assertTrue("missing $file (working dir ${File(".").absolutePath})", file.isFile)

        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
        val intents = document.getElementsByTagName("intent")
        assertTrue("no <intent> elements found in $file", intents.length > 0)

        for (i in 0 until intents.length) {
            val targetPackage = (intents.item(i) as Element).getAttributeNS(ANDROID_NS, "targetPackage")
            assertEquals("$file intent #$i has a stale targetPackage", expectedPackage, targetPackage)
        }
    }
}

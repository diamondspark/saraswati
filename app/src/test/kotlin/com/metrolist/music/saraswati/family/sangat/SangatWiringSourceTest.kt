/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — source-level pins on the wiring no JVM test can drive (a live socket, an Activity):
// the socket always goes to the family hub with the device bearer, the policies are consulted, and the live
// watcher never runs in Saral. They fail the build if an upstream merge or a later edit unwires any of it.
package com.metrolist.music.saraswati.family.sangat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SangatWiringSourceTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")
        return file.readText()
    }

    private val client get() = source("src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherClient.kt")
    private val mainActivity get() = source("src/main/kotlin/com/metrolist/music/MainActivity.kt")

    private fun bodyOf(
        text: String,
        signature: String,
    ): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) return text.substring(open, i + 1)
        }
        error("unbalanced braces after $signature")
    }

    @Test
    fun `the socket goes to the hub and ignores the stored server URL`() {
        val getServerUrl = client.substringAfter("private fun getServerUrl()").substringBefore("\n\n")
        assertFalse(getServerUrl.contains("ListenTogetherServerUrlKey"))
        assertTrue(getServerUrl.contains("DEFAULT_SERVER_URL"))
    }

    @Test
    fun `the upgrade carries the device bearer and needs a session and the kill-switch`() {
        val open = bodyOf(client, "private suspend fun openSocket")
        assertTrue(open.contains(""".header("Authorization", "Bearer ${'$'}token")"""))
        assertTrue(open.contains("identity.sessionToken()"))
        assertTrue(open.contains("featureGate.isEnabled(FamilyFeature.SANGAT)"))
        assertTrue("the connect is refused before the request is built", open.indexOf("failTerminally") < open.indexOf("Request"))
        assertEquals("the bearer is never logged", 0, Regex("""log\([^)]*token""").findAll(open).count())
    }

    @Test
    fun `reconnects and auto-approvals go through the policies`() {
        assertTrue(bodyOf(client, "private fun handleConnectionFailure").contains("SangatReconnectPolicy.shouldReconnect(httpCode, null)"))
        assertTrue(bodyOf(client, "private fun handleDisconnect").contains("SangatReconnectPolicy.shouldReconnect(null, closeCode)"))
        assertEquals(2, Regex("""SangatApprovalPolicy\.autoApprove\(""").findAll(client).count())
    }

    @Test
    fun `the live watcher starts in one place, and only for the full app in the foreground`() {
        assertEquals(1, Regex("""sangatLiveWatcher\.start\(""").findAll(mainActivity).count())
        val sync = bodyOf(mainActivity, "private fun syncSangatWatcher")
        assertTrue(sync.contains("inForeground && !grandparentRole"))
        assertTrue(sync.contains("sangatLiveWatcher.start(lifecycleScope)"))
        assertTrue(bodyOf(mainActivity, "override fun onStop").contains("syncSangatWatcher()"))
        assertTrue(bodyOf(mainActivity, "override fun onStart").contains("syncSangatWatcher()"))
        assertTrue(bodyOf(mainActivity, "private fun applyGrandparentRole").contains("syncSangatWatcher()"))
    }

    @Test
    fun `the gate and the owner's self-pairing run on every resume`() {
        val resumed = bodyOf(mainActivity, "repeatOnLifecycle(Lifecycle.State.RESUMED)")
        assertTrue(Regex("""launch\s*\{\s*featureGate\.refresh\(\)\s*\}""").containsMatchIn(resumed))
        assertTrue(Regex("""launch\s*\{\s*familyViewModel\.ensureSelfPaired\(""").containsMatchIn(resumed))
    }
}

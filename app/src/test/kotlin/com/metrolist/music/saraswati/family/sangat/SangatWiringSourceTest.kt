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
    fun `the upgrade is planned from the gate and the session, and built by the tested function`() {
        val open = bodyOf(client, "private suspend fun openSocket")
        assertTrue(open.contains("identity.sessionToken()"))
        assertTrue(open.contains("featureGate.isEnabled(FamilyFeature.SANGAT)"))
        assertTrue(open.contains("featureGate.awaitHydrated()"))
        assertTrue("the plan comes before the request", open.indexOf("SangatConnect.plan(") in 0 until open.indexOf("SangatConnect.request("))
        assertFalse("no request is built by hand", open.contains("Request\n") || open.contains("Request.Builder"))
        assertEquals("the bearer is never logged", 0, Regex("""log\([^)]*token""").findAll(open).count())
        assertTrue(source("src/main/kotlin/com/metrolist/music/saraswati/family/sangat/SangatConnect.kt").contains(""".header("Authorization", "Bearer ${'$'}token")"""))
    }

    @Test
    fun `the socket's OkHttp client never follows a redirect`() {
        val clientField = client.substringAfter("private val client =").substringBefore("private fun normalizeServerUrl")
        assertTrue(clientField.contains("SangatConnect"))
        assertTrue(clientField.contains(".hardened("))
    }

    @Test
    fun `reconnects and auto-approvals go through the policies`() {
        assertTrue(bodyOf(client, "private fun handleConnectionFailure").contains("SangatConnect.afterFailure(httpCode, null)"))
        assertTrue(bodyOf(client, "private fun handleDisconnect").contains("SangatConnect.afterFailure(null, closeCode)"))
        assertEquals(2, Regex("""SangatApprovalPolicy\.autoApprove\(""").findAll(client).count())
    }

    @Test
    fun `the live watcher starts in one place, and only for the full app in the foreground`() {
        val foreground = source("src/main/kotlin/com/metrolist/music/saraswati/family/sangat/SangatForeground.kt")
        assertEquals(1, Regex("""watcher\.start\(""").findAll(foreground).count())
        assertTrue(bodyOf(foreground, "private fun sync").contains("started > 0 && !saral"))
        assertFalse("MainActivity does not start it itself", mainActivity.contains("sangatLiveWatcher"))
        assertTrue(bodyOf(mainActivity, "override fun onStop").contains("sangatForeground.activityStopped()"))
        assertTrue(bodyOf(mainActivity, "override fun onStart").contains("sangatForeground.activityStarted()"))
        assertTrue(bodyOf(mainActivity, "private fun applyGrandparentRole").contains("sangatForeground.setSaral(value)"))
    }

    @Test
    fun `the gate and the owner's self-pairing run on every resume`() {
        val resumed = bodyOf(mainActivity, "repeatOnLifecycle(Lifecycle.State.RESUMED)")
        // Final fix wave (I2): first in the job that then writes the top-songs playlist, which waits for the hub's answer.
        assertTrue(Regex("""launch\s*\{\s*featureGate\.refresh\(\)""").containsMatchIn(resumed))
        assertTrue(resumed.indexOf("featureGate.refresh()") < resumed.indexOf("topSongsPlaylistSync.syncIfDue("))
        assertTrue(Regex("""launch\s*\{\s*familyViewModel\.ensureSelfPaired\(""").containsMatchIn(resumed))
    }

    @Test
    fun `the prompt names a home room as a home room, with its own copy`() {
        val prompt = source("src/main/kotlin/com/metrolist/music/saraswati/family/sangat/CarSangatPrompt.kt")
        assertEquals(3, Regex("""if \(room\.carMode\)""").findAll(prompt).count())
        assertTrue(prompt.contains("R.string.family_sangat_home_prompt_title"))
        assertTrue(prompt.contains("R.string.family_sangat_home_prompt_hindi"))
        assertTrue(prompt.contains("R.string.family_sangat_home_prompt_desc"))
    }
}

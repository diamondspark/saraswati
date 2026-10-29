/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — source-level pins on the wiring no JVM test can drive (Application.onCreate, an Activity, the
// Firebase SDK): Firebase is initialised before any delivery, a failed registration is retried when the app comes to the
// foreground, and a tap on the Sangat notification brings Home up. They fail the build if a later edit unwires any of it.
package com.metrolist.music.saraswati.family.sangat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SangatPushWiringSourceTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")
        return file.readText()
    }

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

    private val app get() = source("src/main/kotlin/com/metrolist/music/App.kt")
    private val mainActivity get() = source("src/main/kotlin/com/metrolist/music/MainActivity.kt")
    private val registrar get() = source("src/gms/kotlin/com/metrolist/music/saraswati/family/sangat/SangatPushRegistrar.kt")

    @Test
    fun `Firebase is initialised from Application onCreate, in the main process, before anything waits on pairing`() {
        val onCreate = bodyOf(app, "override fun onCreate()")
        val mainProcessGuard = onCreate.indexOf("if (!isMainProcess()) return")
        val start = onCreate.indexOf("sangatPushRegistrar.start(applicationScope)")
        assertTrue(mainProcessGuard in 0 until start)

        val startBody = bodyOf(registrar, "fun start(appScope: CoroutineScope)")
        assertTrue(startBody.indexOf("if (!isConfigured) return") in 0 until startBody.indexOf("firebaseApp()"))
        assertTrue("initialised before the coroutine that needs a session", startBody.indexOf("firebaseApp()") < startBody.indexOf("appScope.launch"))
    }

    @Test
    fun `Firebase is never touched when the build is not configured`() {
        val fetch = bodyOf(registrar, "private suspend fun fetchToken()")
        assertTrue(fetch.contains("firebaseApp()"))
        for (entry in listOf("suspend fun ensureRegistered()", "fun ensureRegisteredAsync()", "suspend fun onNewToken(token: String)", "fun start(appScope")) {
            assertTrue("$entry checks isConfigured first", bodyOf(registrar, entry).trimStart('{').trimStart().startsWith("if (!isConfigured) return"))
        }
    }

    @Test
    fun `the app retries a registration each time it comes to the foreground`() {
        assertTrue(bodyOf(mainActivity, "override fun onStart()").contains("sangatPushRegistrar.ensureRegisteredAsync()"))
    }

    @Test
    fun `a tap on the Sangat notification brings Home up, and Saral is dropped before that`() {
        val onNewIntent = bodyOf(mainActivity, "override fun onNewIntent(intent: Intent)")
        val saral = onNewIntent.indexOf("dropIntentsForSaral(intent)")
        val offer = onNewIntent.indexOf("SangatJoinRequests.offerFrom(")
        val tick = onNewIntent.indexOf("sangatJoinTaps.intValue++")
        assertTrue(saral in 0 until offer)
        assertTrue(offer in 0 until tick)
        // The activity's lateinit navController is never assigned, so Home must be brought up from the composition.
        assertFalse(onNewIntent.contains("showHomeForSangatJoin"))
        val effect = mainActivity.substring(mainActivity.indexOf("LaunchedEffect(joinTaps)"))
        val effectBody = effect.substring(0, effect.indexOf("\n                }\n"))
        assertTrue(effectBody.contains("if (joinTaps > 0)"))
        assertTrue(effectBody.contains("showAccountDialog = false"))
        assertTrue(effectBody.contains("showHomeForSangatJoin(navController)"))
        assertTrue(bodyOf(mainActivity, "private fun showHomeForSangatJoin").contains("popBackStack(Screens.Home.route"))
        assertFalse("only onNewIntent ticks; a cold start already opens on Home", mainActivity.contains("sangatJoinTaps.intValue = "))
    }
}

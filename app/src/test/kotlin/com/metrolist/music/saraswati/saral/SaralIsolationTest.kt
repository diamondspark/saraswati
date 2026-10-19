/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9, Review Focus 4 — "Saral exposes a way out to Settings or
// the other tabs". No JVM test can drive a back gesture or a long-press, so
// these read the two files that decide it and fail the build if a later change
// (or an upstream merge) wires a route, a long-press or an intent handler back
// into the grandparent path:
//  - MainActivity must pick Saral over the nav graph from the cached role, and
//    must drop (not queue) every incoming intent while Saral is active. The nav
//    graph, its routes and every deep-link/widget/share/recognition handler
//    live inside the full-app composable, which is not composed at all then.
//  - The Saral screen must not know about navigation, long-press, or starting
//    other screens, and must swallow the back gesture.
// These are source-level checks on purpose: they are what a JVM test can reach.
package com.metrolist.music.saraswati.saral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SaralIsolationTest {
    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/$path"))
        val file = candidates.firstOrNull { it.isFile } ?: error("cannot find $path from ${File(".").absoluteFile}")
        return file.readText()
    }

    private val mainActivity get() = source("src/main/kotlin/com/metrolist/music/MainActivity.kt")
    private val saralScreen get() = source("src/main/kotlin/com/metrolist/music/saraswati/saral/SaralScreen.kt")

    private fun bodyOf(
        text: String,
        signature: String,
    ): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        error("unbalanced braces after $signature")
    }

    @Test
    fun `MainActivity hosts Saral instead of the full app, chosen from the cached role`() {
        val content = bodyOf(mainActivity, "setContent {")

        val choice = content.indexOf("SaralShell.contentFor(")
        val saral = content.indexOf("SaralApp(")
        val full = content.indexOf("MetrolistApp(")
        assertTrue("setContent must ask SaralShell", choice >= 0)
        assertTrue("Saral must be hosted by setContent", saral >= 0)
        assertTrue("the full app must still be hosted by setContent", full >= 0)
        assertTrue("the role is read before either is composed", choice < saral && choice < full)
        assertEquals("the full app is composed in exactly one place", 1, Regex("""MetrolistApp\(""").findAll(content).count())
    }

    @Test
    fun `MainActivity drops incoming intents while Saral is active, before any handler runs`() {
        val onNewIntent = bodyOf(mainActivity, "override fun onNewIntent")

        val guard = onNewIntent.indexOf("SaralShell.routesIncomingIntents(")
        assertTrue("onNewIntent must ask SaralShell", guard >= 0)
        for (handler in listOf("handleWidgetTargetIntent(", "handleDeepLinkIntent(", "handleRecognitionIntent(")) {
            val at = onNewIntent.indexOf(handler)
            assertTrue("$handler must come after the Saral check", at < 0 || guard < at)
        }
        assertTrue("a dropped intent must not be queued for later", onNewIntent.indexOf("pendingIntent = intent") > guard)
    }

    @Test
    fun `a phone that starts in Saral clears its launch intent before the first frame`() {
        val onCreate = bodyOf(mainActivity, "override fun onCreate")

        val read = onCreate.indexOf("dataStore[GrandparentRoleKey]")
        val drop = onCreate.indexOf("dropIntentsForSaral(intent)")
        val content = onCreate.indexOf("setContent {")
        assertTrue("the cached role is read in onCreate", read >= 0)
        assertTrue("the launch intent is cleared when it is on", drop > read)
        assertTrue("both happen before anything is composed", content > drop)
    }

    @Test
    fun `the role is refreshed from the hub on every launch and resume`() {
        val onCreate = bodyOf(mainActivity, "override fun onCreate")

        assertTrue(onCreate.contains("repeatOnLifecycle(Lifecycle.State.RESUMED)"))
        assertTrue(onCreate.contains("grandparentRoleSync.refresh()"))
    }

    @Test
    fun `the launch intent and any queued one are cleared when Saral is on`() {
        val dropper = bodyOf(mainActivity, "private fun dropIntentsForSaral")

        assertTrue(dropper.contains("pendingIntent = null"))
        assertTrue(dropper.contains("intent.data = null"))
        assertTrue(dropper.contains("intent.action = null"))
    }

    @Test
    fun `the full app owns the navigation graph, and Saral is handed the player and nothing else`() {
        val activity = mainActivity
        val fullApp = bodyOf(activity, "private fun MetrolistApp")

        assertTrue("MetrolistApp owns the NavHost", fullApp.contains("NavHost("))
        assertTrue(
            "SaralApp must be called with only the player",
            Regex("""SaralApp\(\s*playerConnection\s*=\s*playerConnectionSnapshot\s*\)""").containsMatchIn(activity),
        )
    }

    @Test
    fun `the Saral screen has no navigation, no long-press and starts no other screen of ours`() {
        val screen = saralScreen
        val forbidden =
            listOf(
                "androidx.navigation",
                "NavController",
                "LocalNavController",
                "combinedClickable",
                "onLongClick",
                "detectTapGestures",
                "Screens.",
                "\"settings\"",
                "SettingsScreen",
                "ui.screens",
                "startActivity(",
                "Intent.ACTION_VIEW",
            )
        for (word in forbidden) {
            assertTrue("SaralScreen.kt must not contain $word", !screen.contains(word))
        }
    }

    @Test
    fun `the Saral screen swallows the back gesture`() {
        val screen = saralScreen

        assertTrue(screen.contains("BackHandler"))
        assertTrue("back must do nothing, not finish the screen", Regex("""BackHandler\s*(\(\s*enabled\s*=\s*true\s*\))?\s*\{\s*(//[^\n]*\n\s*)?\}""").containsMatchIn(screen))
    }

    @Test
    fun `the only thing the Saral screen launches is the speech recogniser`() {
        val screen = saralScreen

        assertTrue(screen.contains("RecognizerIntent.ACTION_RECOGNIZE_SPEECH"))
        assertEquals("one launcher", 1, Regex("""\.launch\(""").findAll(screen).count())
    }
}

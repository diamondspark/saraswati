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
//  - The one other thing Saral hosts is the existing Google sign-in, as bare
//    content (LoginContent), never as a route: back and long-press both return
//    to the tiles, and the WebView inside keeps its Google-only allowlist.
// These are source-level checks on purpose: they are what a JVM test can reach.
package com.metrolist.music.saraswati.saral

import com.metrolist.music.saraswati.security.LoginUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val saralSignIn get() = source("src/main/kotlin/com/metrolist/music/saraswati/saral/SaralSignIn.kt")
    // SARASWATI: security-ask-explanation — the one-time explanation shown before voice search is first routed
    // through Ask Saraswati; it draws UI inside the Saral package, so it is covered by the way-out scan below.
    private val askVoiceExplanationPrompt get() = source("src/main/kotlin/com/metrolist/music/saraswati/saral/AskVoiceExplanationPrompt.kt")
    private val loginScreen get() = source("src/main/kotlin/com/metrolist/music/ui/screens/LoginScreen.kt")
    private val loginUrlPolicy get() = source("src/main/kotlin/com/metrolist/music/saraswati/security/LoginUrlPolicy.kt")

    private companion object {
        // Ways code that draws UI can leave an app without a startActivity call in sight.
        val WAYS_OUT_OF_SARAL = listOf("LocalUriHandler", "openUri", "LinkAnnotation", "PendingIntent")
    }

    private fun mainSources(): List<Pair<String, String>> {
        val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.name to it.readText() }.toList()
    }

    /** Fails unless every occurrence of [token] in [text] is inside the braces of the function starting at [signature]. */
    private fun assertOnlyInside(
        text: String,
        signature: String,
        token: String,
    ) {
        val total = Regex(Regex.escape(token)).findAll(text).count()
        val inside = Regex(Regex.escape(token)).findAll(bodyOf(text, signature)).count()
        assertTrue("$token must appear inside $signature", inside >= 1)
        assertEquals("$token appears outside $signature", total, inside)
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
    fun `the role and the station cache are refreshed on every launch and resume, each on its own`() {
        val onCreate = bodyOf(mainActivity, "override fun onCreate")

        val resumed = bodyOf(onCreate, "repeatOnLifecycle(Lifecycle.State.RESUMED)")
        assertTrue(Regex("""launch\s*\{\s*grandparentRoleSync\.refresh\(\)\s*\}""").containsMatchIn(resumed))
        assertTrue(
            "the station refresh must not wait behind the role refresh, or startup",
            Regex("""launch\s*\{\s*stationsRefresher\.refresh\(\)\s*\}""").containsMatchIn(resumed),
        )
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
            ) + WAYS_OUT_OF_SARAL
        for (word in forbidden) {
            assertTrue("SaralScreen.kt must not contain $word", !screen.contains(word))
        }
    }

    // SARASWATI: fix wave A (M1) — more ways a tile, a link in text or a notification could leave the app.
    // None of them is used by the Saral screens today; the list fails the build if one is added.
    @Test
    fun `no Saral screen file can open a link or hand out an intent`() {
        for ((name, text) in
            listOf(
                "SaralScreen.kt" to saralScreen,
                "SaralSignIn.kt" to saralSignIn,
                // SARASWATI: security-ask-explanation
                "AskVoiceExplanationPrompt.kt" to askVoiceExplanationPrompt,
            )) {
            for (word in WAYS_OUT_OF_SARAL) {
                assertTrue("$name must not contain $word", !text.contains(word))
            }
        }
    }

    @Test
    fun `every Saral UI file is covered by the way-out scan`() {
        val dir = listOf(File("src/main/kotlin/com/metrolist/music/saraswati/saral"), File("app/src/main/kotlin/com/metrolist/music/saraswati/saral")).first { it.isDirectory }
        val composableFiles =
            dir
                .listFiles { f -> f.extension == "kt" }
                .orEmpty()
                .filter { it.readText().contains("@Composable") }
                .map { it.name }
                .toSet()

        assertEquals(
            "a new Saral file that draws UI must be added to the scan above",
            setOf("SaralScreen.kt", "SaralSignIn.kt", "AskVoiceExplanationPrompt.kt"),
            composableFiles,
        )
    }

    // SARASWATI: fix wave A (M1) — the new-intent listener of the full app is what follows links, widgets and
    // shares. It lives inside MetrolistApp, which is not composed while Saral is on. Registered anywhere else,
    // it would keep routing intents under Saral.
    @Test
    fun `the new-intent listener is registered only inside the full app's composable`() {
        val everywhere = mainSources().filter { it.second.contains("addOnNewIntentListener") }

        assertEquals("exactly one file registers it", listOf("MainActivity.kt"), everywhere.map { it.first })
        assertOnlyInside(mainActivity, "private fun MetrolistApp", "addOnNewIntentListener")
        assertEquals("and only once", 1, Regex("""addOnNewIntentListener""").findAll(mainActivity).count())
    }

    @Test
    fun `the listener check fails when the listener is moved out of the full app composable`() {
        val inOnCreate = "class A { override fun onCreate() { addOnNewIntentListener(l) }\n private fun MetrolistApp() { } }"
        val inBoth = "class A { override fun onCreate() { addOnNewIntentListener(l) }\n private fun MetrolistApp() { addOnNewIntentListener(l) } }"
        val inside = "class A { override fun onCreate() { }\n private fun MetrolistApp() { addOnNewIntentListener(l) } }"

        assertTrue(runCatching { assertOnlyInside(inOnCreate, "private fun MetrolistApp", "addOnNewIntentListener") }.isFailure)
        assertTrue(runCatching { assertOnlyInside(inBoth, "private fun MetrolistApp", "addOnNewIntentListener") }.isFailure)
        assertOnlyInside(inside, "private fun MetrolistApp", "addOnNewIntentListener")
    }

    @Test
    fun `no other source in the app registers an intent listener on an activity`() {
        val offenders = mainSources().filter { (name, text) -> name != "MainActivity.kt" && Regex("""OnNewIntentListener""").containsMatchIn(text) }

        assertTrue("these files register new-intent listeners: ${offenders.map { it.first }}", offenders.isEmpty())
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

    // --- the sign-in path --------------------------------------------------

    @Test
    fun `Saral hosts the sign-in as bare content, not as a route`() {
        val signIn = saralSignIn

        assertTrue(signIn.contains("LoginContent("))
        assertFalse("the nav-graph entry point needs a NavController", signIn.contains("LoginScreen("))
        for (word in listOf("androidx.navigation", "NavController", "NavHost", "LocalNavController", "Screens.", "\"settings\"", "startActivity(", "Intent.ACTION_VIEW") + WAYS_OUT_OF_SARAL) {
            assertTrue("SaralSignIn.kt must not contain $word", !signIn.contains(word))
        }
    }

    @Test
    fun `back and long-press in the sign-in both return to the tiles, and nothing else`() {
        val signIn = saralSignIn

        val backHandler = signIn.indexOf("BackHandler(onBack = onClose)")
        val content = signIn.indexOf("LoginContent(")
        assertTrue("back is handled here, before the login's own handler", backHandler in 0 until content)
        assertTrue(Regex("""onBack\s*=\s*onClose""").containsMatchIn(signIn.substring(content)))
        assertTrue("long-press must go the same place as back", Regex("""onBackLongPress\s*=\s*onClose""").containsMatchIn(signIn.substring(content)))
        assertTrue("the login needs the insets that only the full app provides", signIn.contains("LocalPlayerAwareWindowInsets provides"))
    }

    @Test
    fun `the Saral screen reaches the sign-in only through its own composable`() {
        val screen = saralScreen

        assertTrue(screen.contains("SaralSignIn("))
        assertFalse(screen.contains("LoginContent("))
        assertFalse(screen.contains("LoginScreen("))
    }

    @Test
    fun `the nav-graph login and Saral's share one WebView, so one allowlist`() {
        val login = loginScreen

        assertTrue(
            "LoginScreen must only delegate to LoginContent",
            Regex("""fun LoginScreen\([^)]*\)\s*=\s*LoginContent\(""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(login),
        )
        assertEquals("one WebView in the file", 1, Regex("""WebView\(webViewContext\)""").findAll(login).count())
    }

    @Test
    fun `the login WebView blocks every navigation outside the Google hosts`() {
        val login = loginScreen

        val override = bodyOf(login, "override fun shouldOverrideUrlLoading")
        val allow = override.indexOf("LoginUrlPolicy.isAllowedNavigation(url.toString())) return false")
        val block = override.indexOf("return true")
        assertTrue("it must return false only for an allowed URL", allow >= 0)
        assertTrue("and block everything else", block > allow)
    }

    @Test
    fun `the login WebView only loads Google addresses, and checks the one that comes from a response`() {
        val login = loginScreen

        val loads = Regex("""loadUrl\(""").findAll(login).count()
        assertEquals("every loadUrl in the file is accounted for below", 2, loads)

        val literals = Regex(""""(https://[^"]+)"""").findAll(login.substringAfter("loadUrl(").substringBefore("},")).map { it.groupValues[1] }.toList()
        assertTrue("the first load names fixed addresses", literals.isNotEmpty())
        for (url in literals) assertTrue("$url must be allowed", LoginUrlPolicy.isAllowedNavigation(url))

        assertTrue(
            "the address taken from a response is checked before it is loaded",
            login.indexOf("LoginUrlPolicy.isAllowedNavigation(absoluteUrl)") in 0 until login.indexOf("loadUrl(absoluteUrl)"),
        )
    }

    @Test
    fun `the login WebView opens no windows, bridge or file access`() {
        val login = loginScreen

        assertFalse(login.contains("addJavascriptInterface("))
        assertFalse(Regex("""setSupportMultipleWindows\(\s*true""").containsMatchIn(login))
        assertFalse(Regex("""allowFileAccess\s*=\s*true""").containsMatchIn(login))
        assertTrue(Regex("""allowContentAccess\s*=\s*false""").containsMatchIn(login))
        assertFalse("no custom file chooser or window handler", login.contains("WebChromeClient"))
    }

    @Test
    fun `the allowlist itself is Google and YouTube hosts only`() {
        val hosts =
            Regex(""""([a-z0-9.]+)"""")
                .findAll(loginUrlPolicy.substringAfter("EXACT_ALLOWED_HOSTS").substringBefore(")"))
                .map { it.groupValues[1] }
                .toList()

        assertTrue(hosts.size >= 8)
        for (host in hosts) {
            assertTrue("$host", host.endsWith(".google.com") || host.endsWith(".youtube.com"))
        }
    }

    @Test
    fun `lookalike, plain-http, credential and non-Google addresses are refused`() {
        val refused =
            listOf(
                "http://accounts.google.com/",
                "https://accounts.google.com.evil.example/",
                "https://evil-accounts.google.com/",
                "https://accounts.google.xyz/",
                "https://user@accounts.google.com/",
                "https://accounts.google.com:8443/",
                "https://www.google.com/",
                "https://example.com/",
                "intent://scan/#Intent;scheme=zxing;end",
                "market://details?id=x",
                "file:///data/data/x",
                "javascript:alert(1)",
                "https://youtube.com.evil.example/",
            )
        for (url in refused) assertFalse(url, LoginUrlPolicy.isAllowedNavigation(url))

        for (url in listOf("https://accounts.google.com/ServiceLogin", "https://accounts.google.co.in/", "https://music.youtube.com/", "https://accounts.youtube.com/")) {
            assertTrue(url, LoginUrlPolicy.isAllowedNavigation(url))
        }
    }
}

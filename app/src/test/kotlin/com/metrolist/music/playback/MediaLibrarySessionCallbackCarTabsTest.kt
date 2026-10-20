/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 11, Review Focus 5 — "the car's Stations or Family folder
// blocks on the network". The tree itself is tested for what it returns
// (CarBrowseTreeTest); this reads the files that wire it in and fails if a later
// change (or an upstream merge) puts a hub call, a wait or a lookup that can
// block on the path of those two folders, or loses the notification that tells
// the car to ask again once the cache has loaded. Source-level on purpose: the
// callback needs a database and Hilt, which a JVM test cannot build.
package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaLibrarySessionCallbackCarTabsTest {
    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/$path"))
        val file = candidates.firstOrNull { it.isFile } ?: error("cannot find $path from ${File(".").absoluteFile}")
        return file.readText()
    }

    private val callback get() = source("src/main/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallback.kt")
    private val service get() = source("src/main/kotlin/com/metrolist/music/playback/MusicService.kt")
    private val tree get() = source("src/main/kotlin/com/metrolist/music/saraswati/car/CarBrowseTree.kt")
    private val stations get() = source("src/main/kotlin/com/metrolist/music/saraswati/car/CarStations.kt")

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
    fun `onGetChildren hands the four tabs to the tree before it looks anything else up`() {
        val onGetChildren = bodyOf(callback, "override fun onGetChildren")

        val tree = onGetChildren.indexOf("carTree.childrenOf(parentId)")
        val database = onGetChildren.indexOf("loadLocalChildren(")
        assertTrue("onGetChildren must ask the tree", tree >= 0)
        assertTrue("and before the database lookups", tree < database)
    }

    @Test
    fun `the callback and the car's tree make no call to the family hub`() {
        for ((name, text) in listOf("callback" to callback, "tree" to tree, "stations" to stations)) {
            for (word in listOf("HubClient", "HubApi", "getStations(", "OkHttp", "okhttp3")) {
                assertFalse("$name must not use $word", text.contains(word))
            }
        }
    }

    @Test
    fun `the Stations path reads the cache's snapshot and never waits`() {
        assertFalse("Stations must not suspend", stations.contains("suspend "))
        assertFalse(stations.contains("runBlocking"))
        assertFalse(stations.contains(".first()"))
        assertTrue("it reads the snapshot", tree.contains("stations.snapshot()"))
        val stationItems = bodyOf(tree, "fun stationItems")
        assertFalse(stationItems.contains("await"))
        assertFalse(stationItems.contains("suspend"))
    }

    @Test
    fun `the tree routes exactly the root and the four tabs, plus the old mixes id`() {
        val childrenOf = bodyOf(tree, "suspend fun childrenOf")

        for (id in listOf("MusicService.ROOT", "MusicService.CAR_LIBRARY", "MusicService.CAR_HOME", "MusicService.CAR_STATIONS", "MusicService.CAR_FAMILY")) {
            assertEquals(id, 1, Regex(Regex.escape(id)).findAll(childrenOf).count())
        }
        assertTrue(childrenOf.contains("MusicService.YOUTUBE_PLAYLIST"))
    }

    @Test
    fun `Home is no longer behind the YouTube-mixes setting`() {
        assertFalse(callback.contains("AndroidAutoYouTubePlaylistsKey"))
        assertFalse(tree.contains("AndroidAutoYouTubePlaylistsKey"))
    }

    @Test
    fun `the service tells the car to ask again when the cache loads or changes`() {
        assertTrue(service.contains("carStationsChanges(stationsCache)"))
        assertTrue(service.contains("notifyChildrenChanged(MusicService.CAR_STATIONS"))
    }

    @Test
    fun `choosing a station checks the id it was handed before playing anything from it`() {
        val onSetMediaItems = bodyOf(callback, "override fun onSetMediaItems")
        val branch = onSetMediaItems.indexOf("MusicService.CAR_STATIONS ->")

        assertTrue("onSetMediaItems must have a station branch", branch >= 0)
        val rest = onSetMediaItems.substring(branch)
        val check = rest.indexOf("CarStations.seedFrom(")
        val radio = rest.indexOf("radioEndpointFor(")
        assertTrue("the seed is read through seedFrom", check >= 0)
        assertTrue("and only then is a radio built", radio > check)
    }

    // --- SARASWATI: fix wave B2 -------------------------------------------------------------------------

    private val homeSnapshot get() = source("src/main/kotlin/com/metrolist/music/saraswati/car/CarHomeSnapshot.kt")
    private val androidAutoSettings get() = source("src/main/kotlin/com/metrolist/music/ui/screens/settings/AndroidAutoSettings.kt")

    @Test
    fun `the Home tab is answered from the snapshot, never from the fetch itself`() {
        assertTrue(callback.contains("homeShelves = { listOfNotNull(CarPraharRow.item(praharStore.cached(), LocalDateTime.now(), carResources)) + homeSnapshot.current() }"))
        assertFalse("the tree is not handed the fetch", callback.contains("homeShelves = ::loadHomeShelves"))
        val fetchSite = Regex("""fetch\s*=\s*::loadHomeShelves""").findAll(callback).count()
        assertEquals("the fetch is only the snapshot's background refresh", 1, fetchSite)
    }

    @Test
    fun `the snapshot's refresh is limited to eight seconds and runs in the background`() {
        assertTrue(homeSnapshot.contains("withTimeoutOrNull(timeoutMs) { fetch() }"))
        assertTrue(homeSnapshot.contains("FETCH_TIMEOUT_MS = 8_000L"))
        assertTrue(homeSnapshot.contains("scope.launch(io) { refresh(startedIn) }"))
        val current = bodyOf(homeSnapshot, "fun current()")
        assertFalse("current() cannot wait", current.contains("runBlocking") || current.contains("await") || current.contains("suspend"))
    }

    @Test
    fun `the service tells the car to ask again when Home has a new snapshot`() {
        assertTrue(service.contains("mediaLibrarySessionCallback.homeChanges.collect"))
        assertTrue(service.contains("notifyChildrenChanged(MusicService.CAR_HOME"))
    }

    // The behaviour (memory, file and a refresh in flight) is tested in CarHomeSnapshotTest; App.forgetAccount cannot run
    // on the JVM, so this only pins that it reaches the holder, and only once the YouTube auth state is already cleared.
    @Test
    fun `signing out reaches the live Home snapshot, after the account's auth state is cleared`() {
        val app = source("src/main/kotlin/com/metrolist/music/App.kt")
        val forget = bodyOf(app, "suspend fun forgetAccount")

        val authCleared = forget.indexOf("YouTube.authUser = \"0\"")
        val holder = forget.indexOf("CarHomeHolder.forget(context)")
        assertTrue("forgetAccount clears the car's Home", holder >= 0)
        assertTrue("after the auth state, so a refresh started from then on is anonymous", holder > authCleared && authCleared >= 0)
        assertTrue("the callback registers its snapshot where the holder can find it", callback.contains(".also(CarHomeHolder::register)"))
        assertTrue(callback.contains("CarHomeHolder.unregister(homeSnapshot)"))
    }

    @Test
    fun `the Android Auto settings no longer offer the dead YouTube-playlists switch, and the key is kept for old backups`() {
        assertFalse(androidAutoSettings.contains("AndroidAutoYouTubePlaylistsKey"))
        assertFalse(androidAutoSettings.contains("android_auto_youtube_playlists"))
        assertTrue(source("src/main/kotlin/com/metrolist/music/constants/PreferenceKeys.kt").contains("val AndroidAutoYouTubePlaylistsKey = booleanPreferencesKey(\"androidAutoYoutubePlaylists\")"))
    }

    @Test
    fun `every edit this fork makes to the callback's helpers and imports carries a marker`() {
        val text = callback

        val drawable = text.indexOf("private fun drawableUri(")
        assertTrue("drawableUri is marked", text.substring(0, drawable).takeLast(400).contains("SARASWATI"))
        assertTrue("so is the removed-import note at the top", text.substringBefore("package com.metrolist").contains("SARASWATI"))
        assertTrue(text.substringBefore("package com.metrolist").contains("ContentResolver"))
        assertTrue(text.substringBefore("package com.metrolist").contains("YouTube-playlists preference"))
    }
}

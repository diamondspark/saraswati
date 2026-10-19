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
}

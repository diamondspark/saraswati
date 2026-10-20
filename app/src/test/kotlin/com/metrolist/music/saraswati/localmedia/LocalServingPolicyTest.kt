/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave P5a C1/I1 — with the Local files switch off the car and the queue never hand out a local song,
// and in a room the car's "On this phone" tap cannot start one. The decisions are tested directly; the source checks
// pin that the car callback and MusicService really call them (the callback needs Hilt, which a JVM test cannot build).
package com.metrolist.music.saraswati.localmedia

import androidx.media3.common.MediaItem
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalServingPolicyTest {
    private fun status(index: Int, vararg ids: String) =
        Queue.Status(null, ids.map { MediaItem.Builder().setMediaId(it).build() }, index, position = 1_000L)

    @Test
    fun `local positions come last first`() {
        assertEquals(listOf(3, 1), LocalServingPolicy.localIndicesLastFirst(listOf("a", "local:1", "b", "local:2")))
        assertEquals(emptyList<Int>(), LocalServingPolicy.localIndicesLastFirst(listOf("a", "b")))
    }

    @Test
    fun `car lookup of a local id is refused while not serving and never reaches the database`() =
        runBlocking {
            var lookups = 0
            val found = LocalServingPolicy.lookup("local:5", canServe = { false }) { lookups++; "row" }
            assertNull(found)
            assertEquals(0, lookups)
        }

    @Test
    fun `car lookup of a local id works while serving and a remote id is never gated`() =
        runBlocking {
            assertEquals("row", LocalServingPolicy.lookup("local:5", canServe = { true }) { "row" })
            var asked = 0
            assertEquals("row", LocalServingPolicy.lookup("dQw4w9WgXcQ", canServe = { asked++; false }) { "row" })
            assertEquals(0, asked)
        }

    @Test
    fun `a queue starts without local songs when not serving, and whole when serving outside a room`() {
        val s = status(1, "local:1", "a", "local:2", "b")
        val off = LocalServingPolicy.startableQueue(s, serving = false, inRoom = false)!!
        assertEquals(listOf("a", "b"), off.items.map { it.mediaId })
        assertEquals("a", off.items[off.mediaItemIndex].mediaId)
        assertEquals(s, LocalServingPolicy.startableQueue(s, serving = true, inRoom = false))
        assertNull(LocalServingPolicy.startableQueue(status(0, "local:1"), serving = false, inRoom = false))
        assertNull(LocalServingPolicy.startableQueue(status(0, "local:1"), serving = true, inRoom = true))
    }

    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")
        return file.readText()
    }

    private fun bodyOf(text: String, signature: String): String {
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

    private val callback get() = source("src/main/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallback.kt")
    private val service get() = source("src/main/kotlin/com/metrolist/music/playback/MusicService.kt")

    @Test
    fun `onGetItem goes through the serving gate`() {
        val body = bodyOf(callback, "override fun onGetItem")
        assertTrue(body.contains("LocalServingPolicy.lookup("))
        assertTrue(body.contains("canServe = localSongs::canServe"))
        assertFalse("onGetItem must not read the song directly", body.contains("database.song(mediaId)"))
    }

    @Test
    fun `the car's On this phone tap is filtered for a Sangat room`() {
        val body = bodyOf(callback, "override fun onSetMediaItems")
        val branch = body.indexOf("MusicService.CAR_LOCAL ->")
        assertTrue(branch >= 0)
        val rest = body.substring(branch, body.indexOf("MusicService.CAR_STATIONS ->"))
        assertTrue(rest.contains("LocalSangatPolicy.filterForRoom("))
        assertTrue(rest.contains("service.listenTogetherManager.isInRoom"))
    }

    @Test
    fun `both searches rely on the DAO query that excludes local rows`() {
        // The two searchSongsExtended call sites in the callback; the query itself is covered by LocalSongsHiddenQueriesTest.
        assertEquals(2, Regex("database\\.searchSongsExtended\\(").findAll(callback).count())
    }

    @Test
    fun `MusicService follows the serving flag, drops local songs and gates playQueue and the data source`() {
        assertTrue(service.contains("localSongsRepository.serving.collect"))
        assertTrue(service.contains("dropLocalSongsFromQueue()"))
        assertTrue(service.contains("LocalServingPolicy.startableQueue("))
        assertTrue(service.contains("canServe = { runBlocking { localSongsRepository.canServe() } }"))
    }

    // SARASWATI: fix wave P5a I4/I5.
    @Test
    fun `a local player error skips or stops and never retries through the network`() {
        assertEquals(LocalErrorAction.SKIP, LocalServingPolicy.errorAction("local:3", autoSkipOnError = true))
        assertEquals(LocalErrorAction.STOP, LocalServingPolicy.errorAction("local:3", autoSkipOnError = false))
        assertEquals(LocalErrorAction.NOT_LOCAL, LocalServingPolicy.errorAction("dQw4w9WgXcQ", autoSkipOnError = true))
        assertEquals(LocalErrorAction.NOT_LOCAL, LocalServingPolicy.errorAction(null, autoSkipOnError = false))
    }

    @Test
    fun `onPlayerError handles a local song before any stream refresh or network wait, with a toast`() {
        // The service's own listener is the last onPlayerError in the file; an earlier one belongs to a helper object.
        val service = service
        val body = bodyOf(service.substring(service.lastIndexOf("override fun onPlayerError(error: PlaybackException)")), "override fun onPlayerError(error: PlaybackException)")
        val local = body.indexOf("LocalServingPolicy.errorAction(")
        assertTrue(local >= 0)
        for (later in listOf("hasExceededRetryLimit(", "performAggressiveCacheClear(", "waitOnNetworkError()", "handleStreamClientError(", "refreshStreamAndRetry")) {
            val at = body.indexOf(later)
            assertTrue("$later must come after the local check", at < 0 || at > local)
        }
        val localBlock = body.substring(local, body.indexOf("val failedStreamClient"))
        assertTrue(localBlock.contains("R.string.local_song_cant_play"))
        assertTrue(localBlock.contains("skipOnError()") && localBlock.contains("stopOnError()"))
    }

    @Test
    fun `Cast tells the person when a song on this phone cannot be cast`() {
        val cast = source("src/gms/kotlin/com/metrolist/music/playback/CastConnectionHandler.kt")
        val failure = cast.substring(cast.indexOf("Unable to resolve the Cast queue"))
        assertTrue(failure.indexOf("LocalSongIds.isLocal(metadata.id)") in 0 until failure.indexOf("return@launch"))
        assertTrue(failure.substring(0, failure.indexOf("return@launch")).contains("R.string.local_song_cant_cast"))
    }
}

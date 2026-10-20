/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave P5a I1/I2 — a song on this phone is never named to a Sangat room (playback actions, buffer
// signals, suggestions, queues), and a room cannot make this phone open one of its own audio rows by naming it.
package com.metrolist.music.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalTracksNeverInRoomTest {
    private fun track(id: String) = TrackInfo(id, "t-$id", "artist", "", 1000, "")

    @Test
    fun `a playback action naming a local track id is refused`() {
        assertNull(outboundPlaybackOrNull("local:123", null, null))
    }

    @Test
    fun `a playback action carrying a local TrackInfo is refused`() {
        assertNull(outboundPlaybackOrNull(null, track("local:9"), listOf(track("a"))))
    }

    @Test
    fun `a playback action keeps its remote track and queue without local entries`() {
        val out = outboundPlaybackOrNull("dQw4w9WgXcQ", track("dQw4w9WgXcQ"), listOf(track("a"), track("local:1"), track("b")))
        assertNotNull(out)
        assertEquals(listOf("a", "b"), out!!.queue!!.map { it.id })
    }

    @Test
    fun `a play with no track id and no queue still goes`() {
        val out = outboundPlaybackOrNull(null, null, null)
        assertNotNull(out)
        assertNull(out!!.queue)
    }

    @Test
    fun `inbound tracks naming local songs are dropped`() {
        assertNull(shareableTrackOrNull(track("local:5")))
        assertEquals("abc", shareableTrackOrNull(track("abc"))!!.id)
        assertEquals(listOf("x", "y"), shareableTracks(listOf(track("local:1"), track("x"), track("local:2"), track("y"))).map { it.id })
    }

    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")
        return file.readText()
    }

    private fun bodyOf(text: String, signature: String): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "$signature not found" }
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

    private val client get() = source("src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherClient.kt")
    private val manager get() = source("src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherManager.kt")

    @Test
    fun `every outbound client entry point checks before it sends`() {
        val action = bodyOf(client, "fun sendPlaybackAction(")
        assertTrue(action.indexOf("outboundPlaybackOrNull(") in 0 until action.indexOf("sendMessage("))
        val buffer = bodyOf(client, "fun sendBufferReady(")
        assertTrue(buffer.indexOf("LocalSangatPolicy.mayShare(") in 0 until buffer.indexOf("sendMessage("))
        val suggest = bodyOf(client, "fun suggestTrack(")
        assertTrue(suggest.indexOf("shareableTrackOrNull(") in 0 until suggest.indexOf("sendMessage("))
    }

    @Test
    fun `inbound room tracks are filtered before any player or lookup is built from them`() {
        // The two entry points every inbound track goes through, and the single place action tracks are read.
        assertTrue(bodyOf(manager, "private fun applyPlaybackState(").contains("shareableTrackOrNull(currentTrack)"))
        assertTrue(bodyOf(manager, "private fun applyPlaybackState(").contains("queue?.let(::shareableTracks)"))
        assertTrue(manager.contains("= applyShareableUpcomingQueue(connection, shareableTracks(queue), queueTitle)"))
        assertTrue(manager.contains("val actionTrackInfo = shareableTrackOrNull(action.trackInfoOrNull)"))
        assertTrue(manager.contains("val queue = action.queue?.let(::shareableTracks)"))
    }

    @Test
    fun `no inbound path turns a TrackInfo into a MediaItem outside the filtered functions`() {
        val converting = Regex("""toMediaMetadata\(\)\s*\.toMediaItem\(\)""").findAll(manager).map { it.range.first }.toList()
        val safeFunctions =
            listOf("private fun applyShareableUpcomingQueue(", "private fun applyShareablePlaybackState(")
                .map { sig -> manager.indexOf(sig).let { start -> start..(start + bodyOf(manager, sig).length + 200) } }
        assertTrue("expected the conversion sites", converting.isNotEmpty())
        for (site in converting) {
            assertTrue("a TrackInfo -> MediaItem conversion at $site is outside the filtered functions", safeFunctions.any { site in it })
        }
    }
}

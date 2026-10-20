/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 5 — songs on this phone for the Cast relay: their bytes, ranges, and routing.
package com.metrolist.music.saraswati.castrelay

import com.metrolist.music.saraswati.localmedia.LocalFile
import com.metrolist.music.saraswati.localmedia.LocalFileOpener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class LocalRelayAudioSourceTest {
    private val bytes = ByteArray(100) { it.toByte() }

    private val opener =
        LocalFileOpener { id ->
            if (id == 7L) LocalFile("audio/mp4", bytes.size.toLong(), ByteArrayInputStream(bytes)) else null
        }

    @Test
    fun `serves a local file with its MediaStore mime type and length`() =
        runBlocking {
            val stream = LocalRelayAudioSource(opener).open("local:7", null)!!
            assertEquals("audio/mp4", stream.mimeType)
            assertEquals(100L, stream.totalLength)
            assertEquals(bytes.toList(), stream.input.readBytes().toList())
        }

    @Test
    fun `a range skips to its first byte and limits the length`() =
        runBlocking {
            val stream = LocalRelayAudioSource(opener).open("local:7", 10L..19L)!!
            assertEquals(100L, stream.totalLength)
            assertEquals(bytes.copyOfRange(10, 20).toList(), stream.input.readBytes().toList())
        }

    @Test
    fun `a missing file gives null`() =
        runBlocking {
            assertNull(LocalRelayAudioSource(opener).open("local:8", null))
        }

    @Test
    fun `with the Local files switch off a song on this phone gives null and is not opened`() =
        runBlocking {
            var opened = false
            val source = LocalRelayAudioSource(LocalFileOpener { opened = true; null }, allowed = { false })
            assertNull(source.open("local:7", null))
            assertEquals(false, opened)
        }

    @Test
    fun `a malformed local id gives null without opening anything`() =
        runBlocking {
            var opened = false
            val source = LocalRelayAudioSource(LocalFileOpener { opened = true; null })
            assertNull(source.open("local:abc", null))
            assertEquals(false, opened)
        }

    @Test
    fun `an opener that throws gives null`() =
        runBlocking {
            val source = LocalRelayAudioSource(LocalFileOpener { throw SecurityException("revoked") })
            assertNull(source.open("local:7", null))
        }

    @Test
    fun `routing sends local ids to the local source and video ids to the cache source`() =
        runBlocking {
            val local = RecordingSource()
            val remote = RecordingSource()
            val routing = RoutingRelayAudioSource(local, remote)
            routing.open("local:7", null)
            routing.open("dQw4w9WgXcQ", 0L..9L)
            assertEquals(listOf("local:7"), local.opened)
            assertEquals(listOf("dQw4w9WgXcQ"), remote.opened)
        }

    private class RecordingSource : RelayAudioSource {
        val opened = mutableListOf<String>()

        override suspend fun open(
            videoId: String,
            range: LongRange?,
        ): RelayStream? {
            opened += videoId
            return null
        }
    }
}

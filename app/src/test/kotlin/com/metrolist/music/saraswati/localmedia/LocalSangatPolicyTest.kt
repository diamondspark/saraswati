package com.metrolist.music.saraswati.localmedia

import androidx.media3.common.MediaItem
import com.metrolist.music.listentogether.trackInfoOrNull
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSangatPolicyTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun status(index: Int, vararg ids: String) = Queue.Status(null, ids.map(::item), index, position = 5_000L)

    @Test
    fun `in a room, local items are dropped and the index stays on the same song`() {
        val result = LocalSangatPolicy.filterForRoom(status(3, "local:1", "a", "local:2", "b", "c"), inRoom = true)!!
        assertEquals(listOf("a", "b", "c"), result.items.map { it.mediaId })
        assertEquals("b", result.items[result.mediaItemIndex].mediaId)
        assertEquals(5_000L, result.position)
    }

    @Test
    fun `when the selected song is local the next kept one starts`() {
        val result = LocalSangatPolicy.filterForRoom(status(1, "a", "local:2", "b"), inRoom = true)!!
        assertEquals("b", result.items[result.mediaItemIndex].mediaId)
        assertEquals(0L, result.position)
    }

    @Test
    fun `a room with only local items plays nothing`() {
        assertNull(LocalSangatPolicy.filterForRoom(status(0, "local:1", "local:2"), inRoom = true))
    }

    @Test
    fun `outside a room nothing changes`() {
        val s = status(0, "local:1", "a")
        assertSame(s, LocalSangatPolicy.filterForRoom(s, inRoom = false))
    }

    @Test
    fun `mayShare is false only for local ids`() {
        assertFalse(LocalSangatPolicy.mayShare("local:42"))
        assertTrue(LocalSangatPolicy.mayShare("dQw4w9WgXcQ"))
    }

    @Test
    fun `a local track is never sent as TrackInfo`() {
        val local = MediaMetadata(id = "local:42", title = "My Song", artists = emptyList(), duration = 100)
        val video = MediaMetadata(id = "dQw4w9WgXcQ", title = "Song", artists = emptyList(), duration = 100)
        assertNull(trackInfoOrNull(local, "Artist"))
        val info = trackInfoOrNull(video, "Artist")
        assertNotNull(info)
        assertEquals("dQw4w9WgXcQ", info!!.id)
    }
}

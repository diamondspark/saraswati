/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.resume

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumableQueueTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private class FixedQueue(private val items: List<MediaItem>) : Queue {
        override val preloadItem: MediaMetadata? = null

        override suspend fun getInitialStatus() = Queue.Status(null, items, 0, 0L)

        override fun hasNextPage() = false

        override suspend fun nextPage(): List<MediaItem> = emptyList()
    }

    private fun status(queue: Queue) = runBlocking { queue.getInitialStatus() }

    private fun point(mediaId: String, positionMs: Long = 73_000L) =
        ResumePoint(sourceId = "album", mediaId = mediaId, index = 2, positionMs = positionMs, savedAt = 0L)

    @Test
    fun `resuming starts at the saved song and position`() {
        val queue = ResumableQueue("album", FixedQueue(listOf("a", "b", "c", "d").map(::item)), point("c"))

        val s = status(queue)
        assertEquals(2, s.mediaItemIndex)
        assertEquals(73_000L, s.position)
    }

    @Test
    fun `a saved song no longer in the collection starts from the top`() {
        val queue = ResumableQueue("album", FixedQueue(listOf("a", "b").map(::item)), point("gone"))

        val s = status(queue)
        assertEquals(0, s.mediaItemIndex)
        assertEquals(0L, s.position)
    }

    @Test
    fun `a resume point beyond the first loaded page starts from the top`() {
        val firstPage = listOf("a", "b", "c").map(::item)
        val queue = ResumableQueue("album", FixedQueue(firstPage), point("z-on-page-two"))

        val s = status(queue)
        assertEquals(0, s.mediaItemIndex)
        assertEquals(0L, s.position)
    }

    @Test
    fun `without a resume point the queue starts as its collection says`() {
        val queue = ResumableQueue("album", FixedQueue(listOf("a", "b").map(::item)), null)

        val s = status(queue)
        assertEquals(0, s.mediaItemIndex)
        assertEquals(0L, s.position)
    }
}

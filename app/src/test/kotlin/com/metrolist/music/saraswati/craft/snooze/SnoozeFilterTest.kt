/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.snooze

import androidx.media3.common.MediaItem
import com.metrolist.music.playback.queues.Queue
import org.junit.Assert.assertEquals
import org.junit.Test

class SnoozeFilterTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun status(
        ids: List<String>,
        index: Int,
        position: Long = 4_200L,
    ) = Queue.Status(title = "Test", items = ids.map(::item), mediaItemIndex = index, position = position)

    @Test
    fun `a snoozed song is removed from a loaded queue`() {
        val result = SnoozeFilter.status(status(listOf("a", "b", "c"), index = 0), setOf("b"))

        assertEquals(listOf("a", "c"), result.items.map { it.mediaId })
    }

    @Test
    fun `the song at the start index plays even when snoozed`() {
        val result = SnoozeFilter.status(status(listOf("a", "b", "c"), index = 1), setOf("b"))

        assertEquals(listOf("a", "b", "c"), result.items.map { it.mediaId })
        assertEquals(1, result.mediaItemIndex)
    }

    @Test
    fun `the start index still points at the same song after filtering`() {
        val result = SnoozeFilter.status(status(listOf("a", "b", "c", "d", "e"), index = 3, position = 9_000L), setOf("a", "c"))

        assertEquals(listOf("b", "d", "e"), result.items.map { it.mediaId })
        assertEquals(1, result.mediaItemIndex)
        assertEquals("d", result.items[result.mediaItemIndex].mediaId)
        assertEquals(9_000L, result.position)
    }

    @Test
    fun `a queue where every other song is snoozed keeps only the start song`() {
        val result = SnoozeFilter.status(status(listOf("a", "b", "c"), index = 1), setOf("a", "c"))

        assertEquals(listOf("b"), result.items.map { it.mediaId })
        assertEquals(0, result.mediaItemIndex)
    }

    @Test
    fun `no snoozes leaves the status untouched`() {
        val original = status(listOf("a", "b"), index = 1)

        assertEquals(original, SnoozeFilter.status(original, emptySet()))
    }

    @Test
    fun `next-page and radio items drop snoozed songs`() {
        val items = listOf("a", "b", "c").map(::item)

        assertEquals(listOf("a", "c"), SnoozeFilter.items(items, setOf("b")).map { it.mediaId })
        assertEquals(items, SnoozeFilter.items(items, emptySet()))
    }
}

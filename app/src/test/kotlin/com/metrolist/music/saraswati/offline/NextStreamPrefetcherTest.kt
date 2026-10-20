package com.metrolist.music.saraswati.offline

import com.metrolist.music.saraswati.family.VirtualTimeDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test

class NextStreamPrefetcherTest {
    private val time = VirtualTimeDispatcher()
    private val resolved = mutableListOf<String>()
    private var eligible: (String) -> Boolean = { true }

    private val prefetcher =
        NextStreamPrefetcher(
            scope = CoroutineScope(Job() + time),
            shouldResolve = { eligible(it) },
            resolve = { resolved += it },
        )

    @Test
    fun `resolves the next song 15 s after a track starts`() {
        prefetcher.onTrackStarted("next")
        time.advanceBy(14_999)
        assertEquals(emptyList<String>(), resolved)

        time.advanceBy(1)

        assertEquals(listOf("next"), resolved)
    }

    @Test
    fun `five skips within 15 s make no lookup`() {
        repeat(5) { n ->
            prefetcher.onTrackStarted("song$n")
            time.advanceBy(2_000)
        }
        time.advanceBy(12_999)

        assertEquals(emptyList<String>(), resolved)
        time.advanceBy(1)
        assertEquals(listOf("song4"), resolved)
    }

    @Test
    fun `never resolves a local, downloaded or already resolved song`() {
        eligible = { false }
        prefetcher.onTrackStarted("local:5")
        time.advanceBy(20_000)

        assertEquals(emptyList<String>(), resolved)
    }

    @Test
    fun `no lookup while offline`() {
        eligible = { false }
        prefetcher.onTrackStarted("next")
        time.advanceBy(60_000)

        assertEquals(emptyList<String>(), resolved)
    }

    @Test
    fun `one lookup per track`() {
        prefetcher.onTrackStarted("next")
        time.advanceBy(15_000)
        prefetcher.onTrackStarted("next")
        time.advanceBy(60_000)

        assertEquals(listOf("next"), resolved)
    }

    @Test
    fun `no next song and cancel make no lookup`() {
        prefetcher.onTrackStarted(null)
        prefetcher.onTrackStarted("next")
        prefetcher.cancel()
        time.advanceBy(60_000)

        assertEquals(emptyList<String>(), resolved)
    }
}

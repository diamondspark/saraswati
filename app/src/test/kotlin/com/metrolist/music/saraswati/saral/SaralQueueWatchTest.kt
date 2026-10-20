/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — Saral cannot see a queue fail from the
// player: MusicService loads a new queue in a coroutine whose errors are
// swallowed on purpose (SilentHandler), so a tile tapped with no signal leaves
// the player untouched and silent. QueueWatch wraps the queue Saral starts, so
// "it did not load" and "which tracks are ours" are known to Saral directly.
package com.metrolist.music.saraswati.saral

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

class SaralQueueWatchTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private class FakeQueue(
        var initial: () -> Queue.Status,
        var page: () -> List<MediaItem> = { emptyList() },
        var more: Boolean = true,
    ) : Queue {
        override val preloadItem: MediaMetadata? = null

        override suspend fun getInitialStatus(): Queue.Status = initial()

        override fun hasNextPage(): Boolean = more

        override suspend fun nextPage(): List<MediaItem> = page()
    }

    private fun status(vararg ids: String) = Queue.Status(title = "Radio", items = ids.map(::item), mediaItemIndex = 0)

    @Test
    fun `it passes the queue through unchanged`() =
        runBlocking {
            val watch = QueueWatch()
            val inner = FakeQueue(initial = { status("a", "b") }, more = false)
            val wrapped = watch.wrap(inner)

            val loaded = wrapped.getInitialStatus()

            assertEquals(listOf("a", "b"), loaded.items.map { it.mediaId })
            assertEquals("Radio", loaded.title)
            assertNull(wrapped.preloadItem)
            assertFalse(wrapped.hasNextPage())
        }

    @Test
    fun `the tracks it loads are the ones that belong to Saral`() =
        runBlocking {
            val watch = QueueWatch()
            val wrapped = watch.wrap(FakeQueue(initial = { status("a", "b") }, page = { listOf(item("c")) }))
            assertTrue(watch.ids.value.isEmpty())

            wrapped.getInitialStatus()
            assertEquals(setOf("a", "b"), watch.ids.value)

            wrapped.nextPage()
            assertEquals(setOf("a", "b", "c"), watch.ids.value)
        }

    @Test
    fun `a queue that cannot load offline is reported as offline, and the failure still reaches the player`() {
        val watch = QueueWatch()
        val wrapped = watch.wrap(FakeQueue(initial = { throw UnknownHostException("music.youtube.com") }))

        assertThrows(UnknownHostException::class.java) { runBlocking { wrapped.getInitialStatus() } }

        assertEquals(PlaybackProblem.OFFLINE, watch.problem.value)
        assertTrue(watch.ids.value.isEmpty())
    }

    @Test
    fun `a queue that cannot load for another reason is reported as could not play`() {
        val watch = QueueWatch()
        val wrapped = watch.wrap(FakeQueue(initial = { throw IllegalStateException("empty radio") }))

        assertThrows(IllegalStateException::class.java) { runBlocking { wrapped.getInitialStatus() } }

        assertEquals(PlaybackProblem.COULD_NOT_PLAY, watch.problem.value)
    }

    @Test
    fun `being cancelled is not a failure`() {
        val watch = QueueWatch()
        val wrapped = watch.wrap(FakeQueue(initial = { throw CancellationException("replaced") }))

        assertThrows(CancellationException::class.java) { runBlocking { wrapped.getInitialStatus() } }

        assertNull(watch.problem.value)
    }

    @Test
    fun `a queue with nothing in it could not play`() =
        runBlocking {
            val watch = QueueWatch()
            val wrapped = watch.wrap(FakeQueue(initial = { status() }))

            wrapped.getInitialStatus()

            assertEquals(PlaybackProblem.COULD_NOT_PLAY, watch.problem.value)
        }

    @Test
    fun `a failure to fetch a later page is not a failure of the song that is playing`() {
        val watch = QueueWatch()
        val wrapped = watch.wrap(FakeQueue(initial = { status("a") }, page = { throw IOException("no signal") }))
        runBlocking { wrapped.getInitialStatus() }

        assertThrows(IOException::class.java) { runBlocking { wrapped.nextPage() } }

        assertNull(watch.problem.value)
        assertEquals(setOf("a"), watch.ids.value)
    }

    // --- telling "offline" from "failed" ---------------------------------

    @Test
    fun `network problems are offline`() {
        val offline =
            listOf(
                UnknownHostException("x"),
                ConnectException("x"),
                SocketTimeoutException("x"),
                IOException("x"),
                UnresolvedAddressException(),
                IllegalStateException("wrapped", UnknownHostException("x")),
                RuntimeException("outer", IllegalStateException("middle", IOException("inner"))),
            )
        for (failure in offline) {
            assertTrue("$failure", PlaybackFailures.isOffline(failure))
            assertEquals("$failure", PlaybackProblem.OFFLINE, PlaybackFailures.problemFor(failure))
        }
    }

    @Test
    fun `anything else is a failure of its own`() {
        for (failure in listOf(IllegalStateException("x"), IllegalArgumentException("x"), NullPointerException(), RuntimeException("x", IllegalStateException()))) {
            assertFalse("$failure", PlaybackFailures.isOffline(failure))
            assertEquals("$failure", PlaybackProblem.COULD_NOT_PLAY, PlaybackFailures.problemFor(failure))
        }
    }

    @Test
    fun `a cause that loops back on itself does not hang the check`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertFalse(PlaybackFailures.isOffline(a))
    }

    // SARASWATI: fix wave B2 — the ids were grown with `_ids.value += …`, a read-modify-write that two
    // threads (the preload on one, a page fetch on another) can interleave and so lose an id.
    @Test
    fun `ids added from several threads at once are all kept`() {
        val watch = QueueWatch()
        val threads = 8
        val perThread = 400
        val pages =
            List(threads) { t ->
                watch.wrap(
                    FakeQueue(
                        initial = { status() },
                        page = { List(1) { item("t$t-${pageCounter.incrementAndGet()}") } },
                    ),
                )
            }
        val barrier = java.util.concurrent.CyclicBarrier(threads)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            pages
                .map { queue ->
                    pool.submit {
                        barrier.await()
                        repeat(perThread) { runBlocking { queue.nextPage() } }
                    }
                }.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, watch.ids.value.size)
    }

    private val pageCounter = java.util.concurrent.atomic.AtomicInteger()
}

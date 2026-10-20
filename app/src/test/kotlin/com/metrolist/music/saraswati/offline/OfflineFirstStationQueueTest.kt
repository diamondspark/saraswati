package com.metrolist.music.saraswati.offline

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class OfflineFirstStationQueueTest {
    private class FakeRadio(
        private val items: List<MediaItem>?,
    ) : Queue {
        var initialCalls = 0
        override val preloadItem: MediaMetadata? = null

        override suspend fun getInitialStatus(): Queue.Status {
            initialCalls++
            return Queue.Status("Radio", items ?: error("radio down"), 0)
        }

        override fun hasNextPage() = true

        override suspend fun nextPage() = listOf(MediaItem.Builder().setMediaId("page2").build())
    }

    private fun meta(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 100)

    private val saved = (1..8).map { meta("s$it") }
    private var online = true
    private var downloaded = saved.map { it.id }.toSet() - "s3"

    private fun queue(radio: Queue) =
        OfflineFirstStationQueue("Station", radio, saved, { online }, { it in downloaded }, Random(5))

    private fun ids(status: Queue.Status) = status.items.map { it.mediaId }

    @Test
    fun `online uses the radio queue unchanged`() {
        val radio = FakeRadio(listOf(MediaItem.Builder().setMediaId("r1").build()))
        val queue = queue(radio)

        val status = runBlocking { queue.getInitialStatus() }

        assertEquals("Radio", status.title)
        assertEquals(listOf("r1"), ids(status))
        assertTrue(queue.hasNextPage())
        assertEquals(listOf("page2"), runBlocking { queue.nextPage() }.map { it.mediaId })
    }

    @Test
    fun `offline plays the station's downloaded songs shuffled`() {
        online = false
        val radio = FakeRadio(emptyList())
        val queue = queue(radio)

        val status = runBlocking { queue.getInitialStatus() }

        assertEquals(0, radio.initialCalls)
        assertEquals("Station", status.title)
        assertEquals((downloaded).toSet(), ids(status).toSet())
        assertEquals(7, ids(status).size)
        assertFalse(ids(status) == downloaded.sorted())
        assertFalse(queue.hasNextPage())
        assertEquals(emptyList<MediaItem>(), runBlocking { queue.nextPage() })
    }

    @Test
    fun `a radio failure online falls back to downloaded songs`() {
        val queue = queue(FakeRadio(null))

        val status = runBlocking { queue.getInitialStatus() }

        assertEquals(downloaded, ids(status).toSet())
        assertFalse(queue.hasNextPage())
    }

    @Test
    fun `offline with none downloaded gives an empty status`() {
        online = false
        downloaded = emptySet()

        val status = runBlocking { queue(FakeRadio(emptyList())).getInitialStatus() }

        assertTrue(status.items.isEmpty())
    }
}

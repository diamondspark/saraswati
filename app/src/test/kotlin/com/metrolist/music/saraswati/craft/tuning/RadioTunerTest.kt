/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.tuning

import androidx.media3.common.MediaItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.NextResult
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RadioTunerTest {
    private class FakePlayer(
        var index: Int,
        var ids: List<String>,
    ) : TunablePlayer {
        val adopted = mutableListOf<Queue>()

        override val currentIndex: Int get() = index
        override val currentMediaId: String? get() = ids.getOrNull(index)

        override fun replaceAfterCurrent(items: List<MediaItem>) {
            ids = ids.take(index + 1) + items.map { it.mediaId }
        }

        override fun adoptQueue(queue: Queue) {
            adopted += queue
        }
    }

    private fun song(id: String) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = "")

    private fun radio(vararg ids: String) =
        NextResult(items = ids.map(::song), continuation = null, endpoint = WatchEndpoint(videoId = ids.first()))

    private fun chip(label: String = "Chill", selected: Boolean = false) =
        TuningChip(label = label, endpoint = WatchEndpoint(videoId = "seed-$label"), selected = selected)

    private class Harness(
        player: FakePlayer,
        result: Result<NextResult> = Result.success(NextResult(items = emptyList(), continuation = null, endpoint = WatchEndpoint())),
        snoozed: Set<String> = emptySet(),
        val api: RadioTuningApi = UnconfirmedRadioTuningApi,
    ) {
        var now = 0L
        var calls = 0
        var nextResult = result
        val state = RadioTuningState()
        val tuner =
            RadioTuner(
                state = state,
                next = {
                    calls++
                    nextResult
                },
                player = player,
                snooze = { snoozed },
                clock = { now },
                api = api,
            )
    }

    @Test
    fun `selecting a chip replaces only the songs after the current one`() = runBlocking {
        val player = FakePlayer(index = 1, ids = listOf("a", "b", "c", "d"))
        val h = Harness(player, Result.success(radio("x", "y", "z")))

        assertEquals(TuneResult.Tuned, h.tuner.select(chip()))

        assertEquals(listOf("a", "b", "x", "y", "z"), player.ids)
    }

    @Test
    fun `the current song keeps playing at its position`() = runBlocking {
        val player = FakePlayer(index = 1, ids = listOf("a", "b", "c"))
        val h = Harness(player, Result.success(radio("b", "x", "c")))

        h.tuner.select(chip())

        assertEquals(1, player.currentIndex)
        assertEquals("b", player.currentMediaId)
        assertEquals(listOf("a", "b", "x", "c"), player.ids)
    }

    @Test
    fun `snoozed songs and the current song are left out of the new radio`() = runBlocking {
        val player = FakePlayer(index = 0, ids = listOf("a", "b"))
        val h = Harness(player, Result.success(radio("a", "s", "x")), snoozed = setOf("s"))

        h.tuner.select(chip())

        assertEquals(listOf("a", "x"), player.ids)
    }

    @Test
    fun `a second tap within one second makes no second call`() = runBlocking {
        val player = FakePlayer(index = 0, ids = listOf("a"))
        val h = Harness(player, Result.success(radio("x")))

        assertEquals(TuneResult.Tuned, h.tuner.select(chip("One")))
        h.now = 999L
        assertEquals(TuneResult.Busy, h.tuner.select(chip("Two")))

        assertEquals(1, h.calls)
    }

    @Test
    fun `tapping the selected chip clears the selection and makes no call`() = runBlocking {
        val player = FakePlayer(index = 0, ids = listOf("a"))
        val h = Harness(player, Result.success(radio("x")))
        h.state.publish(listOf(chip("One", selected = true), chip("Two")))

        assertEquals(TuneResult.Unchanged, h.tuner.select(chip("One", selected = true)))

        assertEquals(0, h.calls)
        assertEquals(listOf("a"), player.ids)
        assertEquals(listOf(false, false), h.state.chips.value.map { it.selected })
        assertEquals(listOf("One", "Two"), h.state.chips.value.map { it.label })
    }

    @Test
    fun `the tuned queue continues past its first batch`() = runBlocking {
        val player = FakePlayer(index = 0, ids = listOf("a"))
        val tuned = NextResult(items = listOf(song("x")), continuation = "page-2", endpoint = WatchEndpoint(videoId = "x"))
        val h = Harness(player, Result.success(tuned))

        h.tuner.select(chip())

        assertTrue((player.adopted.single() as YouTubeQueue).hasNextPage())
    }

    @Test
    fun `a failed call leaves the queue untouched`() = runBlocking {
        val player = FakePlayer(index = 0, ids = listOf("a", "b"))
        val h = Harness(player, Result.failure(IllegalStateException("offline")))

        assertEquals(TuneResult.Failed, h.tuner.select(chip()))

        assertEquals(listOf("a", "b"), player.ids)
        assertTrue(player.adopted.isEmpty())
    }

    @Test
    fun `a successful tune adopts a YouTube queue for the chip`() = runBlocking {
        val player = FakePlayer(index = 0, ids = listOf("a"))
        val h = Harness(player, Result.success(radio("x")))

        h.tuner.select(chip())

        assertEquals(1, player.adopted.size)
        assertTrue(player.adopted.single() is YouTubeQueue)
    }

    @Test
    fun `chips clear when a non-radio queue starts`() {
        val state = RadioTuningState()
        state.publish(listOf(chip()))

        state.clear()

        assertTrue(state.chips.value.isEmpty())
    }

    @Test
    fun `the unconfirmed api yields no chips`() {
        assertTrue(UnconfirmedRadioTuningApi.chips(radio("x")).isEmpty())
    }
}

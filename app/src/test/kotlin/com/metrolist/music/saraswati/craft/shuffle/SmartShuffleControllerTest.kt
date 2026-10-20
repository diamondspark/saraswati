package com.metrolist.music.saraswati.craft.shuffle

import androidx.media3.common.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmartShuffleControllerTest {
    private class FakePlayer(ids: List<String>) : ShufflePlayer {
        val list = ids.map { MediaItem.Builder().setMediaId(it).build() }.toMutableList()
        override var currentIndex = 0
        var shuffleOn = false
        var lastOrder: IntArray? = null

        override val count: Int get() = list.size
        override fun mediaIdAt(i: Int): String = list[i].mediaId
        override fun isPick(i: Int): Boolean =
            list[i].mediaMetadata.extras?.getBoolean(SmartShuffle.EXTRA_SMART_PICK) == true

        override fun append(items: List<MediaItem>) {
            list += items
        }

        override fun remove(indices: List<Int>) {
            indices.sortedDescending().forEach {
                list.removeAt(it)
                if (it < currentIndex) currentIndex--
            }
        }

        override fun setOrder(order: IntArray) {
            this.lastOrder = order
        }

        override fun setShuffle(enabled: Boolean) {
            shuffleOn = enabled
        }
    }

    private class Harness(
        val player: FakePlayer,
        val result: () -> Result<List<MediaItem>> = { Result.success(candidates) },
        var snoozed: Set<String> = emptySet(),
        var now: Long = 0L,
    ) {
        var calls = 0
        var lastSeed: String? = null
        val controller =
            SmartShuffleController(
                player = player,
                picks = { seed ->
                    calls++
                    lastSeed = seed
                    result()
                },
                snooze = { snoozed },
                clock = { now },
            )

        companion object {
            val candidates = listOf("c1", "c2", "c3", "c4", "c5").map { MediaItem.Builder().setMediaId(it).build() }
        }
    }

    private fun ownSongs(n: Int) = List(n) { "s$it" }

    private fun ids(player: FakePlayer) = player.list.map { it.mediaId }

    @Test
    fun `the current song stays first and keeps playing`() = runBlocking {
        val player = FakePlayer(ownSongs(10)).apply { currentIndex = 2 }
        val h = Harness(player)

        h.controller.setMode(ShuffleMode.SMART)

        assertEquals(ShuffleMode.SMART, h.controller.mode.value)
        assertEquals(2, player.currentIndex)
        assertEquals(13, player.count)
        assertEquals(2, player.lastOrder!![0])
        assertTrue(player.shuffleOn)
    }

    @Test
    fun `picks already in the queue or snoozed are skipped`() = runBlocking {
        val player = FakePlayer(ownSongs(10))
        val h = Harness(
            player,
            result = { Result.success(listOf("s1", "snz", "x1", "x2", "x3").map { MediaItem.Builder().setMediaId(it).build() }) },
            snoozed = setOf("snz"),
        )

        h.controller.setMode(ShuffleMode.SMART)

        assertEquals(listOf("x1", "x2", "x3"), ids(player).drop(10))
    }

    @Test
    fun `turning smart shuffle off removes only unplayed picks`() = runBlocking {
        val player = FakePlayer(ownSongs(10))
        val h = Harness(player)
        h.controller.setMode(ShuffleMode.SMART)
        val firstPick = player.mediaIdAt(10)
        val playedPick = player.mediaIdAt(11)
        player.currentIndex = 11
        h.controller.onCurrentItemChanged()
        player.currentIndex = 0

        h.controller.setMode(ShuffleMode.OFF)

        assertEquals(ownSongs(10) + playedPick, ids(player))
        assertFalse(ids(player).contains(firstPick))
        assertFalse(player.shuffleOn)
        assertEquals(ShuffleMode.OFF, h.controller.mode.value)
    }

    @Test
    fun `a pick that is playing when smart shuffle turns off keeps playing`() = runBlocking {
        val player = FakePlayer(ownSongs(10))
        val h = Harness(player)
        h.controller.setMode(ShuffleMode.SMART)
        player.currentIndex = 11

        h.controller.setMode(ShuffleMode.OFF)

        assertEquals("c2", player.mediaIdAt(player.currentIndex))
        assertEquals(11, player.count)
    }

    @Test
    fun `enabling twice within 60 seconds fetches once`() = runBlocking {
        val player = FakePlayer(ownSongs(10))
        val h = Harness(player, now = 1_000L)

        h.controller.setMode(ShuffleMode.SMART)
        h.controller.setMode(ShuffleMode.SHUFFLE)
        h.now += 30_000L
        h.controller.setMode(ShuffleMode.SMART)

        assertEquals(1, h.calls)
        assertEquals(ShuffleMode.SMART, h.controller.mode.value)
        assertEquals(13, player.count)
    }

    @Test
    fun `a failed fetch leaves plain shuffle on`() = runBlocking {
        val player = FakePlayer(ownSongs(10))
        val h = Harness(player, result = { Result.failure(IllegalStateException("offline")) })

        h.controller.setMode(ShuffleMode.SMART)

        assertEquals(ShuffleMode.SHUFFLE, h.controller.mode.value)
        assertTrue(player.shuffleOn)
        assertEquals(10, player.count)
        assertNull(player.lastOrder)
    }

    @Test
    fun `a queue of three or fewer songs gets no picks`() = runBlocking {
        val player = FakePlayer(ownSongs(3))
        val h = Harness(player)

        h.controller.setMode(ShuffleMode.SMART)

        assertEquals(0, h.calls)
        assertEquals(ShuffleMode.SHUFFLE, h.controller.mode.value)
        assertEquals(3, player.count)
    }

    @Test
    fun `local songs never reach the network`() = runBlocking {
        val player = FakePlayer(listOf("local:a") + ownSongs(9))
        val h = Harness(player)

        h.controller.setMode(ShuffleMode.SMART)

        assertEquals(0, h.calls)
        assertEquals(ShuffleMode.SHUFFLE, h.controller.mode.value)
    }

    @Test
    fun `a new queue drops smart back to shuffle or off`() = runBlocking {
        val player = FakePlayer(ownSongs(10))
        val h = Harness(player)

        h.controller.setMode(ShuffleMode.SMART)
        h.controller.onNewQueue(shuffleKept = true)
        assertEquals(ShuffleMode.SHUFFLE, h.controller.mode.value)

        h.controller.setMode(ShuffleMode.SMART)
        h.controller.onNewQueue(shuffleKept = false)
        assertEquals(ShuffleMode.OFF, h.controller.mode.value)
    }
}

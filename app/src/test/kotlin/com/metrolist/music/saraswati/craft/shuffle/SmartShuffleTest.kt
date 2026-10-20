package com.metrolist.music.saraswati.craft.shuffle

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmartShuffleTest {
    private fun song(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun `mode cycles off, shuffle, smart`() {
        assertEquals(ShuffleMode.SHUFFLE, ShuffleMode.OFF.next())
        assertEquals(ShuffleMode.SMART, ShuffleMode.SHUFFLE.next())
        assertEquals(ShuffleMode.OFF, ShuffleMode.SMART.next())
    }

    @Test
    fun `one pick follows every three own songs`() {
        val order = SmartShuffle.order(currentIndex = 0, baseCount = 10, pickStart = 10, pickCount = 3, random = Random(7))

        assertEquals((0 until 13).toList(), order.sorted())
        assertEquals(listOf(10, 11, 12), listOf(order[4], order[8], order[12]))
    }

    @Test
    fun `the current song stays first in the smart order`() {
        val asBase = SmartShuffle.order(currentIndex = 4, baseCount = 10, pickStart = 10, pickCount = 3, random = Random(3))
        assertEquals(4, asBase[0])

        val asPick = SmartShuffle.order(currentIndex = 11, baseCount = 10, pickStart = 10, pickCount = 3, random = Random(3))
        assertEquals(11, asPick[0])
        assertEquals((0 until 13).toList(), asPick.sorted())
    }

    @Test
    fun `picks already in the queue or snoozed are left out of the pool`() {
        val candidates = listOf(song("a"), song("b"), song("c"), song("d"), song("e"))

        val picked = SmartShuffle.pick(candidates, queueIds = setOf("a"), snoozed = setOf("b"), count = 5)

        assertEquals(listOf("c", "d", "e"), picked.map { it.mediaId })
        assertTrue(picked.all { it.mediaMetadata.extras?.getBoolean(SmartShuffle.EXTRA_SMART_PICK) == true })
    }

    @Test
    fun `local songs and repeated candidates are never picked`() {
        val candidates = listOf(song("local:1"), song("x"), song("x"), song("y"))

        val picked = SmartShuffle.pick(candidates, queueIds = emptySet(), snoozed = emptySet(), count = 5)

        assertEquals(listOf("x", "y"), picked.map { it.mediaId })
    }

    @Test
    fun `a queue of three or fewer songs has no pick count`() {
        assertEquals(0, SmartShuffle.pickCount(0))
        assertEquals(0, SmartShuffle.pickCount(1))
        assertEquals(0, SmartShuffle.pickCount(3))
        assertEquals(1, SmartShuffle.pickCount(4))
        assertEquals(SmartShuffle.PICKS_MAX, SmartShuffle.pickCount(1_000))
    }
}

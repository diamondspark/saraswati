package com.metrolist.music.saraswati.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random
import org.junit.Test

class OfflineBridgeTest {
    @Test
    fun `picks up to 10 distinct songs not in the queue`() {
        val pool = (1..30).map { "s$it" } + listOf("s1", "s2")
        val exclude = setOf("s1", "s2", "s3")

        val picks = OfflineBridge.pick(pool, exclude, Random(7))

        assertEquals(10, picks.size)
        assertEquals(10, picks.toSet().size)
        assertTrue(picks.none { it in exclude })
        assertEquals(listOf("s5", "s6"), OfflineBridge.pick(listOf("s5", "s6", "s5"), emptySet(), Random(1)).sorted())
    }

    @Test
    fun `an empty pool gives nothing`() {
        assertEquals(emptyList<String>(), OfflineBridge.pick(emptyList(), emptySet(), Random(1)))
        assertEquals(emptyList<String>(), OfflineBridge.pick(listOf("a"), setOf("a"), Random(1)))
    }
}

package com.metrolist.music.saraswati.offline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipToPlayableTest {
    private val ids = listOf("a", "b", "c", "d", "e")

    private fun next(
        order: List<Int>,
        playable: Set<String>,
    ) = runBlocking { SkipToPlayable.nextPlayable(order, ids::get) { it in playable } }

    @Test
    fun `finds the next downloaded song after streamed ones`() {
        assertEquals(3, next(listOf(1, 2, 3, 4), setOf("d", "e")))
    }

    @Test
    fun `wraps once in repeat-all and never returns the current index`() {
        val order = SkipToPlayable.orderAfter(listOf(0, 1, 2, 3, 4), current = 3, repeatAll = true)

        assertEquals(listOf(4, 0, 1, 2), order)
        assertEquals(1, next(order, setOf("b", "d")))
        assertEquals(listOf(4), SkipToPlayable.orderAfter(listOf(0, 1, 2, 3, 4), 3, repeatAll = false))
        assertEquals(listOf(2, 0, 1), SkipToPlayable.orderAfter(listOf(3, 2, 0, 1), 3, repeatAll = true))
    }

    @Test
    fun `returns null after one full pass`() {
        assertNull(next(SkipToPlayable.orderAfter(listOf(0, 1, 2, 3, 4), 2, repeatAll = true), emptySet()))
    }

    @Test
    fun `stops scanning at 200`() {
        val asked = mutableListOf<Int>()
        val order = (1..500).toList()

        val found = runBlocking { SkipToPlayable.nextPlayable(order, { "id$it" }) { asked += it.removePrefix("id").toInt(); false } }

        assertNull(found)
        assertEquals(200, asked.size)
        assertEquals(200, SkipToPlayable.MAX_SCAN)
    }
}

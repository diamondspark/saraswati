package com.metrolist.music.saraswati.offline

import com.metrolist.music.saraswati.family.TestClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NeverSilentControllerTest {
    private class FakePort(
        var queue: MutableList<String>,
        var current: Int = 0,
    ) : QueuePort {
        val jumps = mutableListOf<Int>()
        val plays = mutableListOf<Boolean>()
        val inserted = mutableListOf<List<String>>()

        override fun playOrderAfterCurrent() = (current + 1 until queue.size).toList()

        override fun idAt(index: Int) = queue[index]

        override fun currentId() = queue.getOrNull(current)

        override fun jumpTo(index: Int, play: Boolean) {
            jumps += index
            plays += play
            current = index
        }

        override suspend fun insertAfterCurrent(ids: List<String>): Int? {
            inserted += ids
            queue.addAll(current + 1, ids)
            return current + 1
        }
    }

    private val clock = TestClock()
    private val state = NeverSilentState(clock.reading)
    private var casting = false
    private var guest = false
    private var room = false
    private var pool = emptyList<String>()
    private var lookups = 0
    private var offlineIds = emptySet<String>()

    private fun controller(port: FakePort) =
        NeverSilentController(
            port = port,
            playability = { id -> lookups++; id in offlineIds },
            state = state,
            bridgePool = { pool },
            isCasting = { casting },
            isSangatGuest = { guest },
            inRoom = { room },
            random = Random(3),
        )

    private fun port(vararg ids: String) = FakePort(ids.toMutableList())

    @Test
    fun `offline with a downloaded song ahead jumps to it`() {
        val port = port("now", "x", "dl", "y")
        offlineIds = setOf("dl")

        assertTrue(runBlocking { controller(port).onOffline(wasPlaying = true) })

        assertEquals(listOf(2), port.jumps)
        assertTrue(port.inserted.isEmpty())
    }

    @Test
    fun `a paused queue jumps to a playable song without playing it`() {
        val port = port("now", "x", "dl")
        offlineIds = setOf("dl")

        assertTrue(runBlocking { controller(port).onOffline(wasPlaying = false) })

        assertEquals(listOf(2), port.jumps)
        assertEquals(listOf(false), port.plays)
    }

    @Test
    fun `offline with nothing ahead inserts downloads after the current song`() {
        val port = port("now", "x")
        pool = listOf("d1", "d2")

        assertTrue(runBlocking { controller(port).onOffline(wasPlaying = true) })

        assertEquals(listOf(listOf("d1", "d2")), port.inserted.map { it.sorted() })
        assertEquals(1, port.jumps.single())
        assertEquals(2, port.queue.count { it.startsWith("d") })
    }

    @Test
    fun `offline with no downloads returns false`() {
        val port = port("now", "x")

        assertFalse(runBlocking { controller(port).onOffline(wasPlaying = true) })

        assertTrue(port.jumps.isEmpty())
        assertTrue(port.inserted.isEmpty())
    }

    @Test
    fun `a streamed transition while offline skips at once`() {
        val port = port("now", "streamed", "dl")
        port.current = 1
        offlineIds = setOf("dl")

        assertTrue(runBlocking { controller(port).onTransition("streamed", online = false) })

        assertEquals(listOf(2), port.jumps)
    }

    @Test
    fun `a transition to a playable song or while online and healthy does nothing`() {
        val port = port("a", "dl", "b")
        offlineIds = setOf("dl")
        val c = controller(port)

        assertFalse(runBlocking { c.onTransition("dl", online = false) })
        assertFalse(runBlocking { c.onTransition("b", online = true) })
        assertTrue(port.jumps.isEmpty())
    }

    @Test
    fun `two failed songs online make the next streamed one skip`() {
        val port = port("a", "b", "c", "dl")
        offlineIds = setOf("dl")
        val c = controller(port)

        assertFalse(runBlocking { c.onFinalFailure("a", online = true, wasPlaying = true) })
        assertTrue(runBlocking { c.onFinalFailure("b", online = true, wasPlaying = true) })

        assertEquals(listOf(3), port.jumps)
    }

    @Test
    fun `does nothing while casting`() {
        val port = port("now", "dl")
        offlineIds = setOf("dl")
        casting = true
        val c = controller(port)

        assertFalse(runBlocking { c.onOffline(wasPlaying = true) })
        assertFalse(runBlocking { c.onTransition("now", online = false) })
        assertFalse(runBlocking { c.onFinalFailure("now", online = false, wasPlaying = true) })
        assertTrue(port.jumps.isEmpty())
    }

    @Test
    fun `does nothing for a Sangat guest`() {
        val port = port("now", "dl")
        offlineIds = setOf("dl")
        guest = true

        assertFalse(runBlocking { controller(port).onOffline(wasPlaying = true) })

        assertTrue(port.jumps.isEmpty())
    }

    @Test
    fun `never bridges in a room`() {
        val port = port("now", "x")
        pool = listOf("d1")
        room = true

        assertFalse(runBlocking { controller(port).onOffline(wasPlaying = true) })

        assertTrue(port.inserted.isEmpty())
    }

    @Test
    fun `in a room as the host it still jumps to a playable song`() {
        val port = port("now", "dl")
        offlineIds = setOf("dl")
        room = true

        assertTrue(runBlocking { controller(port).onOffline(wasPlaying = true) })

        assertEquals(listOf(1), port.jumps)
    }

    @Test
    fun `a bridge does not bounce straight back to the songs it just played`() {
        val port = port("now", "x")
        pool = listOf("d1", "d2")
        val c = controller(port)
        runBlocking { c.onOffline(wasPlaying = true) }
        port.queue = mutableListOf("d2", "z")
        port.current = 0

        assertFalse(runBlocking { c.onOffline(wasPlaying = true) })
    }

    @Test
    fun `choosing makes no network call`() {
        val port = port("now", "a", "b", "dl")
        offlineIds = setOf("dl")

        runBlocking { controller(port).onOffline(wasPlaying = true) }

        assertEquals(3, lookups)
    }
}

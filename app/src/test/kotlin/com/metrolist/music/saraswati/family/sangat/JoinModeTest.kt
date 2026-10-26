/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — a join by code takes its mode from the room: the live list first, then one fresh hub
// lookup for a code the list does not show yet. Anything that cannot be had is a guest's join, so a normal room still plays.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.LiveSangatRoom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinModeTest {
    private fun room(
        code: String,
        car: Boolean,
    ) = LiveSangatRoom(code, "Papa", "host", car, 1, "2026-10-25T10:00:00.000Z")

    private class Lookups(
        private val answer: suspend () -> List<LiveSangatRoom>?,
    ) {
        var calls = 0

        suspend fun fetch(): List<LiveSangatRoom>? {
            calls++
            return answer()
        }
    }

    private fun resolve(
        code: String,
        live: List<LiveSangatRoom>,
        fresh: Lookups = Lookups { emptyList() },
        timeoutMs: Long = 3_000L,
    ): Boolean = runBlocking { RemoteControllerPolicy.resolveJoinRemote(code, live, timeoutMs) { fresh.fetch() } }

    @Test
    fun `on the list and a car room is a remote-controller join, without asking the hub again`() {
        val fresh = Lookups { emptyList() }
        assertTrue(resolve("CAR1CAR1", listOf(room("CAR1CAR1", car = true)), fresh))
        assertEquals(0, fresh.calls)
    }

    @Test
    fun `on the list and not a car room is a guest join, without asking the hub again`() {
        val fresh = Lookups { listOf(room("NORM1234", car = true)) }
        assertFalse(resolve("NORM1234", listOf(room("NORM1234", car = false)), fresh))
        assertEquals(0, fresh.calls)
    }

    @Test
    fun `absent from the list but the fresh lookup says car is a remote-controller join`() {
        val fresh = Lookups { listOf(room("CAR1CAR1", car = true)) }
        assertTrue(resolve("CAR1CAR1", emptyList(), fresh))
        assertEquals(1, fresh.calls)
    }

    @Test
    fun `absent from the list and the lookup fails is a guest join`() {
        assertFalse(resolve("CAR1CAR1", emptyList(), Lookups { null }))
    }

    @Test
    fun `absent from the list and the lookup throws is a guest join`() {
        val throwing = Lookups { error("hub unreachable") }
        assertFalse(resolve("CAR1CAR1", emptyList(), throwing))
    }

    @Test
    fun `absent from the list and a slow lookup is a guest join within the timeout`() {
        val slow = Lookups {
            delay(1_000L)
            listOf(room("CAR1CAR1", car = true))
        }
        assertFalse(resolve("CAR1CAR1", emptyList(), slow, timeoutMs = 50L))
    }

    @Test
    fun `absent from the list and the fresh lookup says not a car room is a guest join`() {
        assertFalse(resolve("NORM1234", emptyList(), Lookups { listOf(room("NORM1234", car = false)) }))
    }

    @Test
    fun `the fresh lookup must name this code, not another car room`() {
        assertFalse(resolve("NORM1234", emptyList(), Lookups { listOf(room("CAR1CAR1", car = true)) }))
    }

    @Test
    fun `the code is matched whatever its case`() {
        assertTrue(resolve("car1car1", listOf(room("CAR1CAR1", car = true))))
    }

    @Test
    fun `another room being a car room does not make this one one`() {
        val live = listOf(room("CAR1CAR1", car = true), room("NORM1234", car = false))
        assertFalse(resolve("NORM1234", live))
    }
}

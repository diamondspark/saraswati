/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — a leave, a new room or a disconnect during a join's hub lookup stops that join.
package com.metrolist.music.saraswati.family.sangat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinTicketsTest {
    @Test
    fun `a leave during the lookup window prevents the join`() =
        runBlocking {
            val tickets = JoinTickets()
            val lookup = CompletableDeferred<Boolean>()
            var joined = false
            val ticket = tickets.issue()

            val pending = launch { tickets.runIfCurrent(ticket, { lookup.await() }) { joined = true } }
            tickets.cancel()
            lookup.complete(true)
            pending.join()

            assertFalse("the stale join must not land after a leave", joined)
        }

    @Test
    fun `a join with no leave after it lands with the resolved mode`() =
        runBlocking {
            val tickets = JoinTickets()
            var seen: Boolean? = null
            val ticket = tickets.issue()

            val ran = tickets.runIfCurrent(ticket, { true }) { seen = it }

            assertTrue(ran)
            assertEquals(true, seen)
        }

    @Test
    fun `a second join takes over from one still waiting`() =
        runBlocking {
            val tickets = JoinTickets()
            val firstLookup = CompletableDeferred<Boolean>()
            val firstJoined = mutableListOf<Boolean>()
            val secondJoined = mutableListOf<Boolean>()

            val first = tickets.issue()
            val pending = launch { tickets.runIfCurrent(first, { firstLookup.await() }) { firstJoined += it } }
            val second = tickets.issue()
            tickets.runIfCurrent(second, { false }) { secondJoined += it }
            firstLookup.complete(true)
            pending.join()

            assertEquals(emptyList<Boolean>(), firstJoined)
            assertEquals(listOf(false), secondJoined)
        }

    @Test
    fun `a new room during the lookup window prevents the join`() =
        runBlocking {
            val tickets = JoinTickets()
            val lookup = CompletableDeferred<Boolean>()
            var joined = false
            val ticket = tickets.issue()

            val pending = launch { tickets.runIfCurrent(ticket, { lookup.await() }) { joined = true } }
            tickets.cancel()
            lookup.complete(false)
            pending.join()

            assertFalse(joined)
        }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — the notify decision and the freshness of a notification tap, without Android.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.LiveSangatRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SangatNotifyPolicyTest {
    private fun room(code: String) = LiveSangatRoom(code, "Papa", "other", false, 1, "2026-10-25T10:00:00.000Z")

    @Test
    fun `a newly announced room notifies`() {
        assertEquals(listOf("AAAA"), SangatNotifyPolicy.toNotify(listOf(room("AAAA")), emptySet()).map { it.roomCode })
    }

    @Test
    fun `a dismissed room does not notify, and the others still do`() {
        val out = SangatNotifyPolicy.toNotify(listOf(room("AAAA"), room("BBBB")), setOf("AAAA"))
        assertEquals(listOf("BBBB"), out.map { it.roomCode })
    }

    @Test
    fun `nothing announced means nothing to notify`() {
        assertTrue(SangatNotifyPolicy.toNotify(emptyList(), setOf("AAAA")).isEmpty())
    }

    @Test
    fun `a tap is honoured within the time to live and not after it`() {
        assertTrue(SangatNotifyPolicy.isFreshJoin(requestedAt = 1_000L, now = 1_000L))
        assertTrue(SangatNotifyPolicy.isFreshJoin(requestedAt = 1_000L, now = 1_000L + SangatNotifyPolicy.JOIN_REQUEST_TTL_MS))
        assertFalse(SangatNotifyPolicy.isFreshJoin(requestedAt = 1_000L, now = 1_001L + SangatNotifyPolicy.JOIN_REQUEST_TTL_MS))
    }

    @Test
    fun `a tap from the future or a clock that went backwards is not honoured`() {
        assertFalse(SangatNotifyPolicy.isFreshJoin(requestedAt = 2_000L, now = 1_000L))
    }
}

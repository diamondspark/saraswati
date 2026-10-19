/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 13 — a remote-controller passenger's own pick is credited once, after it has really
// played for the history threshold; nobody else's pick ever is.
package com.metrolist.music.saraswati.family.sangat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassengerPlayCreditTest {
    private var now = 0L
    private val credit = PassengerPlayCredit(historyDurationMs = 30_000) { now }
    private val me = "mohit"

    private fun state(
        id: String? = "a",
        by: String? = me,
        playing: Boolean = true,
    ) = credit.onRoomState(id, by, playing, me)

    private fun after(ms: Long) {
        now += ms
    }

    @Test fun `credits my own pick after 30 seconds of playing`() {
        assertNull(state())
        after(29_999)
        assertNull(state())
        after(1)
        assertEquals("a", state())
    }

    @Test fun `never credits another person's pick`() {
        assertNull(state(by = "papa"))
        after(120_000)
        assertNull(state(by = "papa"))
        assertNull(state(by = null))
        after(120_000)
        assertNull(state(by = ""))
    }

    @Test fun `paused time does not count`() {
        state()
        after(20_000)
        state(playing = false)
        after(600_000)
        assertNull(state(playing = false))
        state(playing = true)
        after(9_999)
        assertNull(state())
        after(1)
        assertEquals("a", state())
    }

    @Test fun `credits each play once`() {
        state()
        after(40_000)
        assertEquals("a", state())
        after(40_000)
        assertNull(state())
    }

    @Test fun `a new track starts from zero and is credited on its own`() {
        state(id = "a")
        after(40_000)
        assertEquals("a", state(id = "a"))
        assertNull(state(id = "b"))
        after(30_000)
        assertEquals("b", state(id = "b"))
    }

    @Test fun `a track that played long enough before a quiet room update is credited when it changes`() {
        state(id = "a")
        after(45_000)
        assertEquals("a", state(id = "b"))
    }

    @Test fun `a skipped track is not credited`() {
        state(id = "a")
        after(5_000)
        assertNull(state(id = "b"))
        after(5_000)
        assertNull(state(id = null))
    }

    @Test fun `a blank passenger name credits nothing`() {
        assertNull(credit.onRoomState("a", "", true, ""))
        after(60_000)
        assertNull(credit.onRoomState("a", "", true, ""))
    }
}

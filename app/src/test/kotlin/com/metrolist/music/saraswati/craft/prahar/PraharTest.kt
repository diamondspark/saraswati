/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 6 — the eight windows of the day and their boundaries.
package com.metrolist.music.saraswati.craft.prahar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class PraharTest {
    @Test
    fun `05_59 is Usha and 06_00 is Pratah`() {
        assertEquals(Prahar.USHA, Prahar.of(LocalTime.of(5, 59)))
        assertEquals(Prahar.PRATAH, Prahar.of(LocalTime.of(6, 0)))
    }

    @Test
    fun `23_59 is Pradosh and 00_00 is Nishith`() {
        assertEquals(Prahar.PRADOSH, Prahar.of(LocalTime.of(23, 59)))
        assertEquals(Prahar.NISHITH, Prahar.of(LocalTime.of(0, 0)))
    }

    @Test
    fun `the eight windows cover the day without overlap`() {
        val counts = Prahar.values().associateWith { 0 }.toMutableMap()
        for (minute in 0 until 24 * 60) {
            val prahar = Prahar.of(LocalTime.of(minute / 60, minute % 60))
            counts[prahar] = counts.getValue(prahar) + 1
        }
        assertEquals(Prahar.values().size, counts.size)
        counts.values.forEach { assertEquals(3 * 60, it) }
    }
}

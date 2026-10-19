/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B1 — the resume throttle. MainActivity asks the hub for this phone's role and the
// station list on every RESUMED event, two calls each time the person opens the app; each is allowed once
// a minute. The last attempt is remembered in memory only, so the first call after the process starts
// always runs.
package com.metrolist.music.saraswati.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeThrottleTest {
    private val minute = 60_000L

    @Test
    fun `the first call always passes, even when the clock reads a few seconds after boot`() {
        for (reading in listOf(0L, 1L, 5_000L, 59_999L, 1_000_000L)) {
            assertTrue("clock at $reading", ResumeThrottle(minute) { reading }.tryAcquire())
        }
    }

    @Test
    fun `a second call inside the minute is refused, and one at the minute passes`() {
        val clock = TestClock(10_000)
        val throttle = ResumeThrottle(minute, clock.reading)
        assertTrue(throttle.tryAcquire())

        clock.advance(1)
        assertFalse(throttle.tryAcquire())
        clock.advance(minute - 2)
        assertFalse("59.999 s after the first", throttle.tryAcquire())
        clock.advance(1)
        assertTrue("60 s after the first", throttle.tryAcquire())
    }

    @Test
    fun `a refused call does not push the next window out`() {
        val clock = TestClock()
        val throttle = ResumeThrottle(minute, clock.reading)
        assertTrue(throttle.tryAcquire())

        repeat(10) {
            clock.advance(5_000)
            assertFalse(throttle.tryAcquire())
        }
        clock.nowMs = minute

        assertTrue("measured from the last call that ran, not the last one asked", throttle.tryAcquire())
    }

    @Test
    fun `a call that passes starts a new window`() {
        val clock = TestClock()
        val throttle = ResumeThrottle(minute, clock.reading)
        assertTrue(throttle.tryAcquire())
        clock.advance(minute)
        assertTrue(throttle.tryAcquire())

        clock.advance(minute - 1)

        assertFalse(throttle.tryAcquire())
    }

    @Test
    fun `an interval of zero never refuses`() {
        val throttle = noThrottle()

        repeat(5) { assertTrue(throttle.tryAcquire()) }
    }

    @Test
    fun `the resume interval is one minute`() {
        assertEquals(minute, ResumeThrottle.RESUME_INTERVAL_MS)
    }
}

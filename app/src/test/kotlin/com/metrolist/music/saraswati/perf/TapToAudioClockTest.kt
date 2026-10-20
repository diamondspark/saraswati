/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 12 — tests for the tap-to-audio clock.
package com.metrolist.music.saraswati.perf

import com.metrolist.music.saraswati.perf.TapToAudioClock.TapSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapToAudioClockTest {
    private var time = 0L
    private val clock = TapToAudioClock { time }

    @Test
    fun `a tap starts timing and the first audio ends it`() {
        time = 100
        clock.onTap("a", offline = false)

        time = 350

        assertEquals(TapSample("a", false, 250), clock.onAudioAdvancing())
    }

    @Test
    fun `first audio ends it once`() {
        clock.onTap("a", offline = true)
        time = 40
        clock.onAudioAdvancing()

        time = 60

        assertNull(clock.onAudioAdvancing())
    }

    @Test
    fun `a second tap before audio drops the first`() {
        clock.onTap("a", offline = false)
        time = 100
        clock.onTap("b", offline = true)

        time = 200

        assertEquals(TapSample("b", true, 100), clock.onAudioAdvancing())
    }

    @Test
    fun `samples over 30 s are dropped`() {
        clock.onTap("a", offline = false)
        time = TapToAudioClock.MAX_SAMPLE_MS + 1
        assertNull(clock.onAudioAdvancing())

        clock.onTap("b", offline = false)
        time += TapToAudioClock.MAX_SAMPLE_MS
        assertEquals(TapToAudioClock.MAX_SAMPLE_MS, clock.onAudioAdvancing()?.elapsedMs)
    }

    @Test
    fun `resuming after a pause yields nothing`() {
        clock.onTap("a", offline = false)
        time = 10
        clock.onAudioAdvancing()
        clock.onPause()

        time = 20

        assertNull(clock.onAudioAdvancing())
    }

    @Test
    fun `a pause before audio cancels the tap`() {
        clock.onTap("a", offline = false)
        clock.onPause()

        time = 20

        assertNull(clock.onAudioAdvancing())
    }

    @Test
    fun `offline is decided at tap time`() {
        clock.onTap("a", offline = true)

        time = 5

        assertEquals(true, clock.onAudioAdvancing()?.offline)
    }
}

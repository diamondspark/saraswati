package com.metrolist.music.saraswati.offline

import com.metrolist.music.saraswati.family.TestClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeverSilentStateTest {
    private val clock = TestClock()
    private val state = NeverSilentState(clock.reading)

    @Test
    fun `two final failures within 10 minutes prefer offline`() {
        state.onFinalFailure("a")
        clock.advance(599_000)
        state.onFinalFailure("b")

        assertTrue(state.preferOffline(online = true))
    }

    @Test
    fun `one failure does not`() {
        state.onFinalFailure("a")
        state.onFinalFailure("a")

        assertFalse(state.preferOffline(online = true))
    }

    @Test
    fun `failures ten minutes apart do not add up`() {
        state.onFinalFailure("a")
        clock.advance(NeverSilentState.DEGRADED_WINDOW_MS)
        state.onFinalFailure("b")

        assertFalse(state.preferOffline(online = true))
    }

    @Test
    fun `a network playback start clears it`() {
        state.onFinalFailure("a")
        state.onFinalFailure("b")
        state.onNetworkPlaybackStarted()

        assertFalse(state.preferOffline(online = true))
    }

    @Test
    fun `offline always prefers offline`() {
        assertTrue(state.preferOffline(online = false))
    }
}

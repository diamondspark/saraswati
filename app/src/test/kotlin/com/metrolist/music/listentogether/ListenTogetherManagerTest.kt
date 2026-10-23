/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat resync fix — regression coverage for hostReconnectResync(). Before this
// fix, a reconnecting host only resent its state when actively playing, so a reconnect that
// landed while paused sent nothing: any drift or staleness already on the server (from before
// the disconnect) was never corrected until the next real play/pause/seek. A joiner connecting
// during that window saw a stale or wrong broadcast.
package com.metrolist.music.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostReconnectResyncTest {
    @Test
    fun `a paused host reconnecting still resyncs instead of sending nothing`() {
        val resync =
            hostReconnectResync(
                localTrackId = "track-1",
                serverTrackId = "track-1",
                isPlaying = false,
                position = 42_000L,
            )

        assertEquals(PlaybackActions.PAUSE, resync.action)
        assertFalse("track already matches, no track-change needed", resync.trackChangeNeeded)
        assertEquals(42_000L, resync.position)
        assertEquals("track-1", resync.trackId)
    }

    @Test
    fun `a playing host reconnecting sends PLAY at its real position`() {
        val resync =
            hostReconnectResync(
                localTrackId = "track-1",
                serverTrackId = "track-1",
                isPlaying = true,
                position = 10_000L,
            )

        assertEquals(PlaybackActions.PLAY, resync.action)
        assertEquals(10_000L, resync.position)
    }

    @Test
    fun `a stale server track triggers a track change regardless of play state`() {
        val resync =
            hostReconnectResync(
                localTrackId = "new-track",
                serverTrackId = "stale-track-from-before-the-disconnect",
                isPlaying = false,
                position = 0L,
            )

        assertTrue("local track differs from what the server has, must resync it", resync.trackChangeNeeded)
        assertEquals(PlaybackActions.PAUSE, resync.action)
        assertEquals("new-track", resync.trackId)
    }

    @Test
    fun `a null server track (fresh room, nothing sent yet) always needs a track change`() {
        val resync =
            hostReconnectResync(
                localTrackId = "track-1",
                serverTrackId = null,
                isPlaying = true,
                position = 5_000L,
            )

        assertTrue(resync.trackChangeNeeded)
        assertEquals(PlaybackActions.PLAY, resync.action)
    }
}

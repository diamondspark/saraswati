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

// SARASWATI: Sangat resync fix — these drive performHostReconnectResync itself, the function
// ListenTogetherManager's Reconnected and HostChanged handlers actually call, by capturing its
// two real effects (sendTrackChange, sendPlaybackAction) as plain lambdas. A real
// ListenTogetherClient/Player isn't available to a JVM unit test (ListenTogetherClient is a
// concrete Hilt-injected class with no test seam), so this is the closest thing to driving the
// actual handler wiring: unlike HostReconnectResyncTest above, these tests would catch a
// regression back to the old `if (isPlaying)`-gated one-sided send, because they assert on
// what's actually invoked, not just on hostReconnectResync's return value.
class PerformHostReconnectResyncTest {
    // SARASWATI: Sangat resync fix — both effects record into ONE ordered list. The hub resets a
    // room to position-0-paused on a track change and rejects a PLAY/PAUSE whose track id doesn't
    // match the room's current track as stale_track (worker/src/sangat/playback.ts), so when a
    // resync needs both, the track change must reach the server before the play/pause — order
    // matters, not just that both happened.
    private sealed interface Effect {
        data object TrackChange : Effect

        data class SentAction(
            val action: String,
            val trackId: String?,
            val position: Long,
        ) : Effect
    }

    private class Recorder {
        val effects = mutableListOf<Effect>()

        fun run(
            localTrackId: String?,
            serverTrackId: String?,
            isPlaying: Boolean,
            position: Long,
        ) {
            performHostReconnectResync(
                localTrackId = localTrackId,
                serverTrackId = serverTrackId,
                isPlaying = isPlaying,
                position = position,
                sendTrackChange = { effects += Effect.TrackChange },
                sendPlaybackAction = { action, trackId, pos -> effects += Effect.SentAction(action, trackId, pos) },
            )
        }
    }

    @Test
    fun `a paused host reconnecting actually sends a PAUSE action, not nothing`() {
        val recorder = Recorder()

        recorder.run(localTrackId = "track-1", serverTrackId = "track-1", isPlaying = false, position = 42_000L)

        assertEquals(listOf(Effect.SentAction(PlaybackActions.PAUSE, "track-1", 42_000L)), recorder.effects)
    }

    @Test
    fun `a playing host reconnecting actually sends a PLAY action`() {
        val recorder = Recorder()

        recorder.run(localTrackId = "track-1", serverTrackId = "track-1", isPlaying = true, position = 10_000L)

        assertEquals(listOf(Effect.SentAction(PlaybackActions.PLAY, "track-1", 10_000L)), recorder.effects)
    }

    @Test
    fun `a stale server track sends the track change before the PAUSE, not just both`() {
        val recorder = Recorder()

        recorder.run(localTrackId = "new-track", serverTrackId = "stale-track", isPlaying = false, position = 0L)

        // Order matters: the hub would reject a PAUSE naming "new-track" as stale_track if it
        // arrived before the hub had already applied the track change.
        assertEquals(
            listOf(Effect.TrackChange, Effect.SentAction(PlaybackActions.PAUSE, "new-track", 0L)),
            recorder.effects,
        )
    }

    @Test
    fun `no player or no current track sends nothing at all, not a crash or a bogus action`() {
        val recorder = Recorder()

        recorder.run(localTrackId = null, serverTrackId = "whatever-the-server-has", isPlaying = true, position = 99_000L)

        assertTrue("nothing should be sent when there's no local track to resync", recorder.effects.isEmpty())
    }
}

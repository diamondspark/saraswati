/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 12 — times one tap from the choice of a song to the first audio it plays, for the speed
// budgets. Pure logic, no Android: the tracer feeds it the player's events. A tap is answered by the first audio
// after it and by nothing else: a second tap before audio replaces the first, a pause cancels it, and a sample over
// MAX_SAMPLE_MS is dropped as a stall, not a speed.
package com.metrolist.music.saraswati.perf

class TapToAudioClock(
    private val now: () -> Long,
) {
    private var pending: PendingTap? = null

    /** A tap on [mediaId]; [offline] is decided by the caller at this moment and kept as given. */
    fun onTap(
        mediaId: String,
        offline: Boolean,
    ) {
        pending = PendingTap(mediaId, offline, now())
    }

    /** The player's audio position started advancing. Yields the sample for the pending tap, once. */
    fun onAudioAdvancing(): TapSample? {
        val tap = pending ?: return null
        pending = null
        val elapsed = now() - tap.startedAt
        if (elapsed > MAX_SAMPLE_MS) return null
        return TapSample(tap.mediaId, tap.offline, elapsed)
    }

    /** The person paused: a tap still waiting for audio is not measured. */
    fun onPause() {
        pending = null
    }

    data class TapSample(
        val mediaId: String,
        val offline: Boolean,
        val elapsedMs: Long,
    )

    private class PendingTap(
        val mediaId: String,
        val offline: Boolean,
        val startedAt: Long,
    )

    companion object {
        const val MAX_SAMPLE_MS = 30_000L
    }
}

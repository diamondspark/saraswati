/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 13 — a remote-controller passenger's phone plays nothing, so no player callback ever
// tells it a song was heard. Its own pick, though, did play in the car: this watches the room state and says
// once when that pick has played for the history threshold, so it can be registered with the passenger's own
// YouTube history. Another person's pick is never credited.
package com.metrolist.music.saraswati.family.sangat

class PassengerPlayCredit(
    private val historyDurationMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var videoId: String? = null
    private var mine = false
    private var playedMs = 0L
    private var credited = false
    private var lastAt = 0L
    private var lastPlaying = false

    /** Returns a video id once, when that play should be credited; null otherwise. */
    fun onRoomState(
        videoId: String?,
        suggestedBy: String?,
        isPlaying: Boolean,
        myUsername: String,
    ): String? {
        val now = clock()
        if (lastPlaying) playedMs += (now - lastAt).coerceAtLeast(0)
        lastAt = now

        var result: String? = null
        if (videoId != this.videoId) {
            // The elapsed time just added belongs to the track that was playing until now.
            result = creditIfDue()
            this.videoId = videoId
            val me = myUsername.trim()
            mine = videoId != null && me.isNotEmpty() && suggestedBy?.trim().equals(me, ignoreCase = true)
            playedMs = 0
            credited = false
        }
        lastPlaying = isPlaying && videoId != null
        return result ?: creditIfDue()
    }

    private fun creditIfDue(): String? {
        val id = videoId ?: return null
        if (!mine || credited || playedMs < historyDurationMs) return null
        credited = true
        return id
    }
}

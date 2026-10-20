/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 3 — what counts as a song on this phone. Private audio must never turn up as music in the
// family car: WhatsApp and Telegram voice notes (under Android/), call and voice recordings, ringtones and
// notification sounds, and anything too short to be a song.
package com.metrolist.music.saraswati.localmedia

object LocalLibraryFilter {
    const val MIN_DURATION_MS = 30_000L

    val EXCLUDED_SEGMENTS =
        setOf(
            "android",
            "recordings",
            "call",
            "calls",
            "call_rec",
            "call recordings",
            "sound_recorder",
            "voice recorder",
            "voice notes",
            "ringtones",
            "notifications",
            "alarms",
        )

    /** A whole path segment must match, so "Recall Songs" stays and "Music/Call" goes. */
    fun accept(row: MediaStoreAudioRow): Boolean {
        if (row.isRecording) return false
        if (row.durationMs < MIN_DURATION_MS) return false
        val segments = row.relativePath.orEmpty().split('/', '\\')
        return segments.none { it.trim().lowercase() in EXCLUDED_SEGMENTS }
    }
}

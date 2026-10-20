/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 4 — "Wahin se": where a speed-dial collection (album, playlist, episode) was left.
// Stored on the phone only. Offers are decided by [ResumePolicy]; storage is in [ResumeStore].
package com.metrolist.music.saraswati.craft.resume

import kotlinx.serialization.Serializable

@Serializable
data class ResumePoint(
    val sourceId: String,
    val mediaId: String,
    val index: Int,
    val positionMs: Long,
    val savedAt: Long,
)

object ResumePolicy {
    private const val OFFER_MIN_POSITION_MS = 30_000L
    private const val FINISHED_WITHIN_MS = 10_000L
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val TTL_MS = ResumeStore.RESUME_TTL_DAYS * DAY_MS

    /** True when [point] is recent enough, and far enough in that resuming is worth offering. */
    fun offer(
        point: ResumePoint?,
        now: Long,
    ): Boolean {
        if (point == null) return false
        if (now - point.savedAt >= TTL_MS) return false
        return point.index > 0 || point.positionMs >= OFFER_MIN_POSITION_MS
    }

    /** True when playback has reached the end of the collection's last song. */
    fun finished(
        index: Int,
        count: Int,
        positionMs: Long,
        durationMs: Long,
    ): Boolean = count > 0 && index == count - 1 && durationMs > 0 && positionMs >= durationMs - FINISHED_WITHIN_MS
}

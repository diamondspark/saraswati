/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 4 — songs on this phone never join a Sangat. Their title would reach the hub, and the
// other phones could not play them. In a room they are dropped from a queue before it starts, and they are never
// built into a TrackInfo.
package com.metrolist.music.saraswati.localmedia

import com.metrolist.music.playback.queues.Queue

object LocalSangatPolicy {
    fun mayShare(mediaId: String): Boolean = !LocalSongIds.isLocal(mediaId)

    /**
     * Outside a room the queue is returned as it is. In a room, local items are dropped and the position stays on
     * the same song (or, if that song was local, on the next one kept). Null when nothing is left to play.
     */
    fun filterForRoom(
        status: Queue.Status,
        inRoom: Boolean,
    ): Queue.Status? {
        if (!inRoom) return status
        val kept = status.items.filter { mayShare(it.mediaId) }
        if (kept.size == status.items.size) return status
        if (kept.isEmpty()) return null
        val selected = status.items.getOrNull(status.mediaItemIndex)
        val keptBefore = status.items.take(status.mediaItemIndex.coerceAtLeast(0)).count { mayShare(it.mediaId) }
        return status.copy(
            items = kept,
            mediaItemIndex = keptBefore.coerceAtMost(kept.size - 1),
            position = if (selected != null && mayShare(selected.mediaId)) status.position else 0L,
        )
    }
}

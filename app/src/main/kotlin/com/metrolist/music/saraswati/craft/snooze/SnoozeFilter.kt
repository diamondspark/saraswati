/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 2 — drops snoozed songs from queues and lists as they enter the player. A queue's
// starting song always plays, even when snoozed, because the person asked for it by name.
package com.metrolist.music.saraswati.craft.snooze

import androidx.media3.common.MediaItem
import com.metrolist.music.playback.queues.Queue

object SnoozeFilter {
    /** Removes snoozed items but keeps the one at [Queue.Status.mediaItemIndex], re-pointing the index at the same song. */
    fun status(
        status: Queue.Status,
        snoozed: Set<String>,
    ): Queue.Status {
        if (snoozed.isEmpty()) return status
        val start = status.mediaItemIndex
        val kept = status.items.filterIndexed { index, item -> index == start || item.mediaId !in snoozed }
        if (kept.size == status.items.size) return status
        val keptBefore = status.items.take(start.coerceAtLeast(0)).count { it.mediaId !in snoozed }
        return status.copy(
            items = kept,
            mediaItemIndex = keptBefore.coerceAtMost(kept.lastIndex.coerceAtLeast(0)),
        )
    }

    fun items(
        items: List<MediaItem>,
        snoozed: Set<String>,
    ): List<MediaItem> = if (snoozed.isEmpty()) items else items.filterNot { it.mediaId in snoozed }
}

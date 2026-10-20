/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 4 — writes the resume point while a resumable collection plays. Pauses, song changes
// and a replaced queue always write; otherwise at most one write per 10 seconds. Reaching the end of the
// collection clears its point instead. Queues that are not resumable are ignored.
package com.metrolist.music.saraswati.craft.resume

import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ResumeRecorder(
    private val store: ResumeStore,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    private var lastSavedAt: Long? = null

    fun onProgress(
        queue: Queue?,
        index: Int,
        mediaId: String?,
        count: Int,
        positionMs: Long,
        durationMs: Long,
        force: Boolean,
    ) {
        if (queue !is ResumableQueue || mediaId == null) return
        if (ResumePolicy.finished(index, count, positionMs, durationMs)) {
            scope.launch { store.clear(queue.sourceId) }
            return
        }
        val now = clock()
        val last = lastSavedAt
        if (!force && last != null && now - last < SAVE_EVERY_MS) return
        lastSavedAt = now
        val point = ResumePoint(queue.sourceId, mediaId, index, positionMs, now)
        scope.launch { store.save(point) }
    }

    private companion object {
        const val SAVE_EVERY_MS = 10_000L
    }
}

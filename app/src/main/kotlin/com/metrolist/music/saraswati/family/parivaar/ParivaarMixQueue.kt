/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — the Parivaar Mix as a play queue: the built songs in order, no further pages.
package com.metrolist.music.saraswati.family.parivaar

import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue

class ParivaarMixQueue(
    private val tracks: List<MixTrack>,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    override suspend fun getInitialStatus() = Queue.Status(TITLE, tracks.map { it.toQueueMetadata().toMediaItem() }, 0)

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage() = throw UnsupportedOperationException()

    companion object {
        const val TITLE = "Parivaar Mix"
    }
}

/** A mix track as the player holds it: flagged, so a finished play is known to be the mix's whatever queue is current. */
internal fun MixTrack.toQueueMetadata(): MediaMetadata = toMediaMetadata().copy(fromParivaarMix = true)

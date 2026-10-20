/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 7 — a Prahar mix as a play queue: the built songs in order, no further pages. Solo taste
// (no flag), like any queue the person starts.
package com.metrolist.music.saraswati.craft.prahar

import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.saraswati.family.parivaar.toMediaMetadata

class PraharQueue(
    val mix: PraharMix,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    override suspend fun getInitialStatus() =
        Queue.Status(title(mix), mix.tracks.map { it.toMediaMetadata().toMediaItem() }, 0)

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage() = throw UnsupportedOperationException()

    companion object {
        fun title(mix: PraharMix): String = "Prahar · ${mix.prahar.hindi}"
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — a window onto the queue Saral starts.
//
// MusicService loads a new queue in a coroutine whose errors are swallowed on
// purpose, so a tile tapped with no signal changes nothing in the player and
// nothing tells Saral. Wrapping the queue fixes both halves: a load that fails
// is seen here (classified offline or not), and every track the queue produces
// is remembered, so "is the track that is playing one of ours" is a lookup
// instead of a guess. The wrapper passes everything through unchanged.
package com.metrolist.music.saraswati.saral

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

internal class QueueWatch {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of every track this queue has produced so far. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    private val _problem = MutableStateFlow<PlaybackProblem?>(null)

    /** Set when this queue could not load at all. */
    val problem: StateFlow<PlaybackProblem?> = _problem.asStateFlow()

    fun wrap(queue: Queue): Queue {
        queue.preloadItem?.let { _ids.value += it.id }
        return Watched(queue)
    }

    /** For a failure that happens before the queue even reaches the player. */
    fun record(failure: Throwable) {
        _problem.value = PlaybackFailures.problemFor(failure)
    }

    private fun seen(items: List<MediaItem>) {
        if (items.isNotEmpty()) _ids.value += items.map { it.mediaId }
    }

    private inner class Watched(
        private val inner: Queue,
    ) : Queue {
        override val preloadItem: MediaMetadata? get() = inner.preloadItem

        override suspend fun getInitialStatus(): Queue.Status =
            try {
                inner.getInitialStatus().also {
                    seen(it.items)
                    if (it.items.isEmpty()) _problem.value = PlaybackProblem.COULD_NOT_PLAY
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                record(e)
                throw e
            }

        override fun hasNextPage(): Boolean = inner.hasNextPage()

        override suspend fun nextPage(): List<MediaItem> = inner.nextPage().also(::seen)
    }
}

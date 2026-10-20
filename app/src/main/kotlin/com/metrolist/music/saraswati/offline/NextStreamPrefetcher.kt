/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 8 — resolves the next song's stream URL once the current song has played for 15 s, so
// the change of song does not wait for a lookup. Modest on purpose, to look like a person listening: a skip
// restarts the wait, so skipping through a queue makes no lookups at all, and a track is looked up at most once.
package com.metrolist.music.saraswati.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class NextStreamPrefetcher(
    private val scope: CoroutineScope,
    private val shouldResolve: suspend (String) -> Boolean,
    private val resolve: suspend (String) -> Unit,
) {
    private val lock = Any()
    private var job: Job? = null
    private var lastLookedUp: String? = null

    /** A track started; [nextId] is the song after it, or null if none. Replaces any wait already running. */
    fun onTrackStarted(nextId: String?) {
        synchronized(lock) {
            job?.cancel()
            job = null
            if (nextId == null || nextId == lastLookedUp) return
            job =
                scope.launch {
                    delay(PREFETCH_DELAY_MS)
                    if (!shouldResolve(nextId)) return@launch
                    synchronized(lock) { lastLookedUp = nextId }
                    try {
                        resolve(nextId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A failed lookup is left to the real playback path; it is not retried here.
                    }
                }
        }
    }

    fun cancel() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }

    companion object {
        const val PREFETCH_DELAY_MS = 15_000L
    }
}

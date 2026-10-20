/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 7 — whether YouTube looks to be failing right now. Two different songs giving up
// within ten minutes means it is, and until a streamed song plays again (or the ten minutes pass) the player
// prefers what it can play without a request. Offline always prefers offline.
package com.metrolist.music.saraswati.offline

class NeverSilentState(
    private val now: () -> Long,
) {
    private val lock = Any()
    private val failures = LinkedHashMap<String, Long>()

    fun onFinalFailure(mediaId: String) =
        synchronized(lock) {
            failures.remove(mediaId)
            failures[mediaId] = now()
            expire()
        }

    fun onNetworkPlaybackStarted() = synchronized(lock) { failures.clear() }

    fun preferOffline(online: Boolean): Boolean =
        !online ||
            synchronized(lock) {
                expire()
                failures.size >= DEGRADED_AFTER_FAILURES
            }

    private fun expire() {
        val cutoff = now() - DEGRADED_WINDOW_MS
        failures.values.removeAll { it <= cutoff }
    }

    companion object {
        const val DEGRADED_AFTER_FAILURES = 2
        const val DEGRADED_WINDOW_MS = 600_000L
    }
}

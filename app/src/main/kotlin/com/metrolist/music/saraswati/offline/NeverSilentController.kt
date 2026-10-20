/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 7 — the decisions behind "never silent". When the signal drops or YouTube fails,
// jump to the next song that can play without a network; if none is ahead, put some downloads after the
// current song and play them; if there are none, return false so upstream's own handling runs unchanged.
//
// Nothing here makes a network call (only [OfflinePlayability] is consulted), nothing acts while casting or
// as a Sangat guest or remote controller, and nothing is added to a queue inside a Sangat room. One decision
// runs at a time: an event that arrives while one is running is counted as handled by it, so a burst of
// player errors cannot start a second scan or a second bridge.
package com.metrolist.music.saraswati.offline

import kotlinx.coroutines.sync.Mutex
import kotlin.random.Random

/** The player's queue as the controller sees it. Every member runs on the main thread. */
interface QueuePort {
    /** Queue indices that play after the current item, in play order (wrapped once under repeat-all, never the current one). */
    fun playOrderAfterCurrent(): List<Int>

    fun idAt(index: Int): String

    fun currentId(): String?

    /** Seeks to [index] and prepares it. Plays only when [play] is true and the player is still meant to play. */
    fun jumpTo(index: Int, play: Boolean)

    /** Puts the songs [ids] right after the current item and returns the queue index of the first, or null if none were added. */
    suspend fun insertAfterCurrent(ids: List<String>): Int?
}

class NeverSilentController(
    private val port: QueuePort,
    private val playability: OfflinePlayability,
    private val state: NeverSilentState,
    private val bridgePool: suspend () -> List<String>,
    private val isCasting: () -> Boolean,
    private val isSangatGuest: () -> Boolean,
    private val inRoom: () -> Boolean,
    private val random: Random = Random.Default,
) {
    private val busy = Mutex()
    private var lastBridge: Set<String> = emptySet()

    /**
     * The network is gone and the current song failed. [wasPlaying] is read when the event arrives, so a pause
     * pressed during the scan is kept. True when this took over.
     */
    suspend fun onOffline(wasPlaying: Boolean): Boolean = act(wasPlaying)

    /** The current song gave up for good. True when this took over. */
    suspend fun onFinalFailure(
        mediaId: String,
        online: Boolean,
        wasPlaying: Boolean,
        youTubeFailed: Boolean,
    ): Boolean {
        if (youTubeFailed) state.onFinalFailure(mediaId)
        return if (state.preferOffline(online)) act(wasPlaying) else false
    }

    /** [mediaId] just became the current song. Acts only if it would have to be streamed and streaming is not wanted. */
    suspend fun onTransition(
        mediaId: String,
        online: Boolean,
    ): Boolean {
        if (isCasting() || isSangatGuest()) return false
        if (!state.preferOffline(online)) return false
        if (playability.isPlayableOffline(mediaId)) return false
        return act(wasPlaying = true)
    }

    private suspend fun act(wasPlaying: Boolean): Boolean {
        if (isCasting() || isSangatGuest()) return false
        if (!busy.tryLock()) return true
        try {
            val startedOn = port.currentId()
            val ahead = port.playOrderAfterCurrent()
            val aheadIds = ahead.map(port::idAt)
            val next = SkipToPlayable.nextPlayable(ahead, port::idAt, playability::isPlayableOffline)
            if (port.currentId() != startedOn) return true // the listener moved on while the scan ran
            if (next != null) {
                port.jumpTo(next, play = wasPlaying)
                return true
            }
            if (inRoom()) return false
            val picks = OfflineBridge.pick(bridgePool(), aheadIds.toSet() + setOfNotNull(startedOn) + lastBridge, random)
            if (picks.isEmpty()) return false
            if (port.currentId() != startedOn) return true
            val first = port.insertAfterCurrent(picks) ?: return false
            lastBridge = picks.toSet()
            port.jumpTo(first, play = wasPlaying)
            return true
        } finally {
            busy.unlock()
        }
    }
}

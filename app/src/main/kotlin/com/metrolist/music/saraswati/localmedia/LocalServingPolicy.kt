/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave P5a C1 — what a phone with the Local files switch off (or access gone) does with songs on this
// phone that are still held somewhere: a queue, the persisted queue, a car lookup. Nothing is read and nothing is
// shown. These are the pure decisions; MusicService and the car callback apply them.
package com.metrolist.music.saraswati.localmedia

import com.metrolist.music.playback.queues.Queue

/** What a player error on a local song leads to. Never a stream refresh or a wait for the network. */
enum class LocalErrorAction { NOT_LOCAL, SKIP, STOP }

object LocalServingPolicy {
    // SARASWATI: fix wave P5a I4 — a local file that cannot be read is not a network problem: retrying through the
    // stream resolver would throw LocalSongEgressException each time, and with no network the player would wait for
    // internet for an unreadable file. The song is skipped (or playback stops) with a toast, so it is never silent.
    fun errorAction(
        mediaId: String?,
        autoSkipOnError: Boolean,
    ): LocalErrorAction =
        when {
            !LocalSongIds.isLocal(mediaId) -> LocalErrorAction.NOT_LOCAL
            autoSkipOnError -> LocalErrorAction.SKIP
            else -> LocalErrorAction.STOP
        }

    /** Positions of local songs in a queue, last first, so removing them one by one keeps the rest in place. */
    fun localIndicesLastFirst(mediaIds: List<String>): List<Int> =
        mediaIds.indices.reversed().filter { LocalSongIds.isLocal(mediaIds[it]) }

    /**
     * A queue as it may start now: local songs are dropped when they cannot be served or the phone is in a Sangat
     * (the position stays on the same song, or the next one kept). Null when nothing is left to play.
     */
    fun startableQueue(
        status: Queue.Status,
        serving: Boolean,
        inRoom: Boolean,
    ): Queue.Status? = LocalSangatPolicy.filterForRoom(status, inRoom = inRoom || !serving)

    /** The car's lookup of one item: a local id is answered only while serving. */
    suspend fun <T : Any> lookup(
        mediaId: String,
        canServe: suspend () -> Boolean,
        find: suspend (String) -> T?,
    ): T? {
        if (LocalSongIds.isLocal(mediaId) && !canServe()) return null
        return find(mediaId)
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — what a remote-controller passenger sees: the room's track, whether it is
// playing, and what is queued next. It is read from the room state the client already keeps up to date, so
// it needs no player and moves with the host's phone, not the passenger's.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.listentogether.RoomState
import com.metrolist.music.listentogether.TrackInfo
import com.metrolist.music.listentogether.currentTrackOrNull

data class RemoteNowPlaying(
    val track: TrackInfo?,
    val isPlaying: Boolean,
    val queue: List<TrackInfo>,
) {
    companion object {
        fun from(state: RoomState?): RemoteNowPlaying? =
            state?.let { RemoteNowPlaying(it.currentTrackOrNull, it.isPlaying, it.queueList) }
    }
}

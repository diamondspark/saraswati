/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 5 — sends `local:` songs to the phone's own files and every other song to the cached
// stream path. A malformed local id still goes to the local source, which refuses it, so it never reaches the network.
package com.metrolist.music.saraswati.castrelay

import com.metrolist.music.saraswati.localmedia.LocalSongIds

class RoutingRelayAudioSource(
    private val local: RelayAudioSource,
    private val remote: RelayAudioSource,
) : RelayAudioSource {
    override suspend fun open(
        videoId: String,
        range: LongRange?,
    ): RelayStream? =
        if (LocalSongIds.isLocal(videoId)) local.open(videoId, range) else remote.open(videoId, range)
}

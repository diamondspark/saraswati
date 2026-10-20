/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 16 — where the relay gets audio bytes. The server never sees a file path or a
// network URL; it asks this source for a song, optionally from a byte offset.
package com.metrolist.music.saraswati.castrelay

import java.io.InputStream

interface RelayAudioSource {
    /**
     * Opens [videoId] for reading. [range] is an inclusive byte range; the returned input starts at its first
     * byte and runs to the end of the song, and the caller stops reading where it needs to. Null when the song
     * cannot be served.
     */
    suspend fun open(
        videoId: String,
        range: LongRange?,
    ): RelayStream?
}

data class RelayStream(
    val mimeType: String,
    val totalLength: Long?,
    val input: InputStream,
)

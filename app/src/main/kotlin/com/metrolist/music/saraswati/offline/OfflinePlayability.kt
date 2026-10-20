/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 7 — "can this song play with no network at all?", answered from the phone's own
// state and never by a request: a local file with the permission, a finished download, or a song whose whole
// stream is already in the player cache.
//
// The local-file test is two small functions here, not the Task 2/3 types (LocalSongIds, LocalAccess), which
// live on another branch; MusicService passes them in. A revoked permission makes a local song unplayable.
package com.metrolist.music.saraswati.offline

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface OfflinePlayability {
    suspend fun isPlayableOffline(mediaId: String): Boolean
}

@UnstableApi
class DefaultOfflinePlayability(
    private val downloads: () -> Map<String, Download>,
    private val playerCache: Cache,
    private val contentLength: suspend (String) -> Long?,
    private val isLocal: (String) -> Boolean,
    private val localGranted: () -> Boolean,
) : OfflinePlayability {
    override suspend fun isPlayableOffline(mediaId: String): Boolean {
        if (isLocal(mediaId)) return localGranted()
        if (downloads()[mediaId]?.state == Download.STATE_COMPLETED) return true
        return withContext(Dispatchers.IO) {
            val length = contentLength(mediaId)?.takeIf { it > 0L } ?: return@withContext false
            playerCache.isCached(mediaId, 0, length)
        }
    }
}

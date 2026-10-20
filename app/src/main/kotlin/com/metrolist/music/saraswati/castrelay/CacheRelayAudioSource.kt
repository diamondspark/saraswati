/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 17 — the relay's audio. Reads a downloaded or cached song, or fetches it live through
// the playback stream path. It uses its own CacheDataSource instance so the relay never shares read state with
// the player, and FLAG_IGNORE_CACHE_ON_ERROR so a damaged cache entry falls back to the network.
package com.metrolist.music.saraswati.castrelay

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

class CacheRelayAudioSource(
    private val cache: Cache,
    private val upstream: DataSource.Factory,
    private val storedMimeType: suspend (videoId: String) -> String?,
) : RelayAudioSource {
    override suspend fun open(
        videoId: String,
        range: LongRange?,
    ): RelayStream? =
        withContext(Dispatchers.IO) {
            val dataSource =
                CacheDataSource
                    .Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
            val position = range?.first?.coerceAtLeast(0L) ?: 0L
            val spec =
                DataSpec
                    .Builder()
                    .setUri(Uri.parse("https://music.youtube.com/watch?v=$videoId"))
                    .setKey(videoId)
                    .setPosition(position)
                    .build()
            try {
                // Opening runs the playback stream path, which also stores the format; the MIME type is read after it.
                val remaining = dataSource.open(spec)
                val mimeType = storedMimeType(videoId)
                if (mimeType == null) {
                    dataSource.close()
                    return@withContext null
                }
                RelayStream(
                    mimeType = mimeType,
                    totalLength = if (remaining == C.LENGTH_UNSET.toLong()) null else position + remaining,
                    input = RelayDataSourceStream(dataSource),
                )
            } catch (e: IOException) {
                runCatching { dataSource.close() }
                null
            }
        }
}

private class RelayDataSourceStream(
    private val dataSource: DataSource,
) : InputStream() {
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        val read = dataSource.read(buffer, offset, length)
        return if (read == C.RESULT_END_OF_INPUT) -1 else read
    }

    override fun close() = dataSource.close()
}

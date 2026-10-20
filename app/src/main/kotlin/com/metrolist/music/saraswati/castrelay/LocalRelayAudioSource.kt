/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 5 — serves a song on this phone to the LAN relay. The file is opened only when a relay
// request asks for it, read straight from its content URI, and never cached or sent anywhere but the relay socket.
package com.metrolist.music.saraswati.castrelay

import com.metrolist.music.saraswati.localmedia.LocalFileOpener
import com.metrolist.music.saraswati.localmedia.LocalSongIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream

// SARASWATI: Phase 5 Task 5 — `allowed` is the Local files switch and access, checked on every open: with the switch
// off, a song already in the queue is not served either.
class LocalRelayAudioSource(
    private val opener: LocalFileOpener,
    private val allowed: suspend () -> Boolean = { true },
) : RelayAudioSource {
    override suspend fun open(
        videoId: String,
        range: LongRange?,
    ): RelayStream? =
        withContext(Dispatchers.IO) {
            if (!allowed()) return@withContext null
            val mediaStoreId = LocalSongIds.mediaStoreId(videoId) ?: return@withContext null
            val file =
                try {
                    opener.open(mediaStoreId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A revoked permission or a vanished file means "cannot serve", never a crash.
                    null
                } ?: return@withContext null

            val start = range?.first?.coerceAtLeast(0L) ?: 0L
            val input = file.input
            input.skipFully(start)
            val bounded: InputStream =
                range?.takeIf { it.last < Long.MAX_VALUE }?.let { LimitedStream(input, it.last - start + 1) } ?: input
            RelayStream(
                mimeType = file.mimeType,
                totalLength = file.length.takeIf { it >= 0 },
                input = bounded,
            )
        }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            when {
                skipped > 0 -> left -= skipped
                read() < 0 -> return
                else -> left--
            }
        }
    }

    private class LimitedStream(
        input: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val byte = super.read()
            if (byte >= 0) remaining--
            return byte
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            if (remaining <= 0) return -1
            val read = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }

        override fun skip(count: Long): Long {
            val skipped = super.skip(minOf(count, remaining))
            remaining -= skipped
            return skipped
        }
    }
}

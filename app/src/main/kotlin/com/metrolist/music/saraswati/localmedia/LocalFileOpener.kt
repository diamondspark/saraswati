/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 5 — opens one song on this phone for the Cast relay. It reads the MediaStore row named by
// the id and the file that row points to, nothing else: no listing, no path, no network.
package com.metrolist.music.saraswati.localmedia

import android.content.ContentResolver
import android.content.ContentUris
import android.content.res.AssetFileDescriptor
import android.provider.MediaStore
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

fun interface LocalFileOpener {
    /** Null when the row or its file is gone, or it has no MIME type. [LocalFile.length] is -1 when unknown. */
    fun open(mediaStoreId: Long): LocalFile?
}

data class LocalFile(
    val mimeType: String,
    val length: Long,
    val input: InputStream,
)

class ContentResolverFileOpener(
    private val resolver: ContentResolver,
) : LocalFileOpener {
    override fun open(mediaStoreId: Long): LocalFile? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
        val row =
            resolver
                .query(uri, arrayOf(MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst() || cursor.isNull(0)) {
                        null
                    } else {
                        MediaRow(cursor.getString(0), if (cursor.isNull(1)) -1L else cursor.getLong(1))
                    }
                } ?: return null
        val afd = resolver.openAssetFileDescriptor(uri, "r") ?: return null
        val stream =
            try {
                afd.createInputStream()
            } catch (e: IOException) {
                afd.close()
                return null
            }
        val length = if (row.size >= 0) row.size else afd.length
        return LocalFile(row.mimeType, length, ClosingStream(stream, afd))
    }

    private data class MediaRow(
        val mimeType: String,
        val size: Long,
    )

    private class ClosingStream(
        input: InputStream,
        private val afd: AssetFileDescriptor,
    ) : FilterInputStream(input) {
        override fun close() {
            try {
                super.close()
            } finally {
                afd.close()
            }
        }
    }
}

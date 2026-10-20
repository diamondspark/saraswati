/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 3 — reads the audio table of MediaStore. Only ever called once access is GRANTED (the
// repository checks); the reader itself does not look at permissions.
package com.metrolist.music.saraswati.localmedia

import android.content.ContentResolver
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface MediaStoreAudioReader {
    suspend fun rows(): List<MediaStoreAudioRow>
}

class ContentResolverAudioReader(
    private val resolver: ContentResolver,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : MediaStoreAudioReader {
    override suspend fun rows(): List<MediaStoreAudioRow> =
        withContext(Dispatchers.IO) {
            val projection =
                buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    add(MediaStore.Audio.Media.ALBUM_ID)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.MIME_TYPE)
                    add(MediaStore.Audio.Media.RELATIVE_PATH)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.DATE_MODIFIED)
                    if (sdkInt >= Build.VERSION_CODES.S) add(MediaStore.Audio.Media.IS_RECORDING)
                }.toTypedArray()
            val result = ArrayList<MediaStoreAudioRow>()
            val cursor =
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    null,
                ) ?: throw IllegalStateException("MediaStore returned no cursor")
            cursor.use {
                val id = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mime = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val path = it.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val name = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val modified = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val recording =
                    if (sdkInt >= Build.VERSION_CODES.S) it.getColumnIndex(MediaStore.Audio.Media.IS_RECORDING) else -1
                while (it.moveToNext()) {
                    result +=
                        MediaStoreAudioRow(
                            id = it.getLong(id),
                            title = it.getStringOrNull(title),
                            artist = it.getStringOrNull(artist),
                            album = it.getStringOrNull(album),
                            albumId = if (it.isNull(albumId)) null else it.getLong(albumId),
                            durationMs = if (it.isNull(duration)) 0L else it.getLong(duration),
                            mimeType = it.getStringOrNull(mime),
                            relativePath = it.getStringOrNull(path),
                            displayName = it.getStringOrNull(name),
                            dateModifiedSec = if (it.isNull(modified)) 0L else it.getLong(modified),
                            isRecording = recording >= 0 && !it.isNull(recording) && it.getInt(recording) != 0,
                        )
                }
            }
            result
        }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}

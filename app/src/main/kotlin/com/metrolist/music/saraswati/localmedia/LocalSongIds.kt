/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 2 — a song on this phone is a `song` row with id `local:<MediaStore _ID>`. The id is
// the location: the content URI is derived from it, so no path is stored and the schema does not change.
package com.metrolist.music.saraswati.localmedia

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

object LocalSongIds {
    const val PREFIX = "local:"

    fun of(mediaStoreId: Long): String = "$PREFIX$mediaStoreId"

    // Any non-empty id under the prefix is treated as local, so a malformed one is still kept off the network.
    fun isLocal(id: String?): Boolean = id != null && id.startsWith(PREFIX) && id.length > PREFIX.length

    fun mediaStoreId(id: String?): Long? {
        if (id == null || !id.startsWith(PREFIX)) return null
        val digits = id.substring(PREFIX.length)
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        return digits.toLongOrNull()
    }

    fun contentUri(id: String): Uri? =
        mediaStoreId(id)?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
}

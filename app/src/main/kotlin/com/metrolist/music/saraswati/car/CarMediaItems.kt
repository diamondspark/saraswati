/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 11 — the two small builders every folder row of the car's
// browse tree is made with. They were private to MediaLibrarySessionCallback;
// the car tabs (CarBrowseTree) need the same rows, so they live here and the
// callback's own helpers call them.
package com.metrolist.music.saraswati.car

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * The words and icons the car rows are made of. Handed in as two functions so the rows can be built, and tested,
 * without an Android resource table.
 */
internal class CarResources(
    val string: (Int) -> String,
    val iconUri: (Int) -> Uri,
) {
    companion object {
        fun of(context: Context) = CarResources(string = { context.getString(it) }, iconUri = { carDrawableUri(context, it) })
    }
}

internal fun carDrawableUri(
    context: Context,
    @DrawableRes id: Int,
): Uri =
    Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

internal fun carBrowsableMediaItem(
    id: String,
    title: String,
    subtitle: String?,
    iconUri: Uri?,
    mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
): MediaItem =
    MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

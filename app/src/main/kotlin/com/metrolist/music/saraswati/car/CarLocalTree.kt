/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 6 — the two local rows of the car's Library (Downloaded, and On this phone) and the
// rows inside On this phone. Plain functions over values already loaded: the car asks Room, never MediaStore or
// the network, and Android Auto gets only android.resource art, never our content URIs.
package com.metrolist.music.saraswati.car

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.music.R
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.playback.MusicService
import com.metrolist.music.saraswati.localmedia.LocalAccess
import kotlin.random.Random

data class CarLocalQueue(
    val songs: List<Song>,
    val startIndex: Int,
)

internal object CarLocalTree {
    fun libraryRows(
        resources: CarResources,
        access: LocalAccess,
        localCount: Int,
        downloadedCount: Int,
    ): List<MediaItem> =
        buildList {
            add(
                carBrowsableMediaItem(
                    "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                    resources.string(R.string.car_downloaded_title),
                    resources.songCount(downloadedCount),
                    resources.iconUri(R.drawable.download),
                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                ),
            )
            val showLocal =
                when (access) {
                    LocalAccess.UNSUPPORTED -> false
                    LocalAccess.NOT_GRANTED -> true
                    LocalAccess.GRANTED -> localCount > 0
                }
            if (showLocal) {
                add(
                    carBrowsableMediaItem(
                        MusicService.CAR_LOCAL,
                        resources.string(R.string.car_local_title),
                        if (access == LocalAccess.NOT_GRANTED) {
                            resources.string(R.string.car_local_allow)
                        } else {
                            resources.songCount(localCount)
                        },
                        resources.iconUri(R.drawable.music_note),
                        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                    ),
                )
            }
        }

    fun children(
        resources: CarResources,
        access: LocalAccess,
        songs: List<Song>,
        includeShuffle: Boolean,
    ): List<MediaItem> =
        when (access) {
            LocalAccess.UNSUPPORTED -> emptyList()
            LocalAccess.NOT_GRANTED ->
                listOf(
                    MediaItem
                        .Builder()
                        .setMediaId("${MusicService.CAR_LOCAL}/${ALLOW_ID}")
                        .setMediaMetadata(
                            MediaMetadata
                                .Builder()
                                .setTitle(resources.string(R.string.car_local_allow))
                                .setArtworkUri(resources.iconUri(R.drawable.music_note))
                                .setIsPlayable(false)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build(),
                        ).build(),
                )
            LocalAccess.GRANTED ->
                buildList {
                    if (includeShuffle) {
                        add(
                            playableRow(
                                resources,
                                "${MusicService.CAR_LOCAL}/${MusicService.SHUFFLE_ACTION}",
                                resources.string(R.string.shuffle),
                                null,
                            ),
                        )
                    }
                    songs.forEach { song ->
                        add(
                            playableRow(
                                resources,
                                "${MusicService.CAR_LOCAL}/${song.id}",
                                song.song.title,
                                song.artists.joinToString(", ") { it.name },
                            ),
                        )
                    }
                }
        }

    /** The queue for a tap on [songId]: every local song in title order, starting from that one. Null when it is not here. */
    fun queueFrom(
        songs: List<Song>,
        songId: String,
    ): CarLocalQueue? {
        val ordered = songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.song.title })
        val index = ordered.indexOfFirst { it.id == songId }
        return if (index < 0) null else CarLocalQueue(ordered, index)
    }

    /** Every local song, shuffled. */
    fun shuffleQueue(
        songs: List<Song>,
        random: Random = Random.Default,
    ): List<Song> = songs.shuffled(random)

    private fun playableRow(
        resources: CarResources,
        id: String,
        title: String,
        subtitle: String?,
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
                    .setArtworkUri(resources.iconUri(R.drawable.music_note))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()

    private const val ALLOW_ID = "allow"
}

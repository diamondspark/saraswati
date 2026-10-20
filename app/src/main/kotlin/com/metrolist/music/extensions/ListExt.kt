/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.db.entities.Song

fun <T> List<T>.reversed(reversed: Boolean) = if (reversed) asReversed() else this

fun <T> MutableList<T>.move(
    fromIndex: Int,
    toIndex: Int,
): MutableList<T> {
    add(toIndex, removeAt(fromIndex))
    return this
}

// Extension function to filter explicit content for local Song entities
fun List<Song>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filter { !it.song.explicit }
    } else {
        this
    }

// Extension function to filter video songs for local Song entities
fun List<Song>.filterVideoSongs(enabled: Boolean = true) =
    if (enabled) {
        filter { !it.song.isVideo }
    } else {
        this
    }

// Extension function to filter explicit content for local Album entities
fun List<Album>.filterExplicitAlbums(enabled: Boolean = true) =
    if (enabled) {
        filter { !it.album.explicit }
    } else {
        this
    }

// Extension function to filter YouTube Shorts playlist
fun List<Playlist>.filterYoutubeShorts(enabled: Boolean = false) =
    if (enabled) {
        filterNot { it.playlist.browseId?.startsWith("SS") == true }
    } else {
        this
    }

// SARASWATI: fix wave P5a C2 — a song on this phone must leave a playlist's own song list, thumbnail grid and
// count the moment the Local files switch goes off, and come back the moment it goes back on.
fun List<Song>.filterLocalSongs(localFilesEnabled: Boolean) =
    if (localFilesEnabled) {
        this
    } else {
        filterNot { it.song.isLocal }
    }

// SARASWATI: fix wave P5a C2 — see filterLocalSongs(List<Song>) above; the PlaylistSong variant for
// playlist_song_map-backed queries. Named differently: List<Song> and List<PlaylistSong> erase to the same JVM
// signature, so an overload here would be a platform declaration clash.
fun List<PlaylistSong>.filterLocalPlaylistSongs(localFilesEnabled: Boolean) =
    if (localFilesEnabled) {
        this
    } else {
        filterNot { it.song.song.isLocal }
    }

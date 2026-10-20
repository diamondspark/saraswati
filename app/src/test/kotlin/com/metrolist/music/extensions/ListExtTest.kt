/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave P5a C2 — filterLocalSongs is the shared mechanism every playlist call site uses to hide a
// song on this phone (isLocal = 1) the moment the Local files switch goes off, and show it again once it is back
// on. This is the one place that logic is tested directly; call sites are combine()d with the switch and verified
// to call this function.
package com.metrolist.music.extensions

import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ListExtTest {
    private fun song(
        id: String,
        isLocal: Boolean,
    ) = Song(SongEntity(id = id, title = "Song $id", isLocal = isLocal), artists = emptyList())

    private fun playlistSong(
        id: String,
        isLocal: Boolean,
    ) = PlaylistSong(PlaylistSongMap(playlistId = "p", songId = id), song(id, isLocal))

    @Test
    fun `filterLocalSongs on List of Song excludes local when off and includes when on`() {
        val songs = listOf(song("yt1", isLocal = false), song("local:1", isLocal = true))

        assertEquals(listOf("yt1"), songs.filterLocalSongs(localFilesEnabled = false).map { it.song.id })
        assertEquals(listOf("yt1", "local:1"), songs.filterLocalSongs(localFilesEnabled = true).map { it.song.id })
    }

    @Test
    fun `filterLocalSongs on List of PlaylistSong excludes local when off and includes when on`() {
        val songs = listOf(playlistSong("yt1", isLocal = false), playlistSong("local:1", isLocal = true))

        assertEquals(listOf("yt1"), songs.filterLocalPlaylistSongs(localFilesEnabled = false).map { it.song.song.id })
        assertEquals(
            listOf("yt1", "local:1"),
            songs.filterLocalPlaylistSongs(localFilesEnabled = true).map { it.song.song.id },
        )
    }
}

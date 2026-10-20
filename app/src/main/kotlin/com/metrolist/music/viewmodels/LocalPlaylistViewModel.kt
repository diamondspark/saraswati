/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.LocalFilesEnabledKey
import com.metrolist.music.constants.PlaylistSongSortDescendingKey
import com.metrolist.music.constants.PlaylistSongSortType
import com.metrolist.music.constants.PlaylistSongSortTypeKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.extensions.filterLocalPlaylistSongs
import com.metrolist.music.extensions.reversed
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.saraswati.localmedia.LocalFiles
import com.metrolist.music.saraswati.localmedia.localAccess
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!
    val playlist =
        database
            .playlist(playlistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _onlinePlaylist = MutableStateFlow<PlaylistItem?>(null)
    val onlinePlaylist: StateFlow<PlaylistItem?> = _onlinePlaylist

    // SARASWATI: fix wave P5a C2 — a song on this phone must leave this song list the moment the Local files
    // switch goes off, and come back the moment it goes back on. This is the song list only: the screen's header
    // count/thumbnails still read playlist.songCount/playlist.thumbnails, an unfiltered query — a known,
    // already tracked follow-up, not fixed here.
    private data class PlaylistSongsPrefs(
        val sortType: PlaylistSongSortType,
        val sortDescending: Boolean,
        val hideVideoSongs: Boolean,
        val localFilesEnabled: Boolean,
    )

    // SARASWATI: fix wave P5a C2 — the position-renumbering pass below must see every song, including ones the
    // switch is hiding right now, or closing a local song's gap would shift remote songs onto its stored position.
    private val rawPlaylistSongs = database.playlistSongs(playlistId)

    val playlistSongs: StateFlow<List<PlaylistSong>> =
        combine(
            rawPlaylistSongs,
            context.dataStore.data
                .map {
                    PlaylistSongsPrefs(
                        sortType = it[PlaylistSongSortTypeKey].toEnum(PlaylistSongSortType.CUSTOM),
                        sortDescending = it[PlaylistSongSortDescendingKey] ?: true,
                        hideVideoSongs = it[HideVideoSongsKey] ?: false,
                        localFilesEnabled = LocalFiles.enabled(it[LocalFilesEnabledKey], localAccess(context)),
                    )
                }.distinctUntilChanged(),
        ) { songs, prefs ->
            val filteredSongs = songs
                .filterLocalPlaylistSongs(prefs.localFilesEnabled)
                .let { filtered ->
                    if (prefs.hideVideoSongs) {
                        filtered.filter { !it.song.song.isVideo }
                    } else {
                        filtered
                    }
                }
            when (prefs.sortType) {
                PlaylistSongSortType.CUSTOM -> filteredSongs
                PlaylistSongSortType.CREATE_DATE -> filteredSongs.sortedBy { it.map.id }
                PlaylistSongSortType.NAME -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs.sortedWith(compareBy(collator) { it.song.song.title })
                }
                PlaylistSongSortType.ARTIST -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs.sortedWith(
                        compareBy<PlaylistSong, String>(collator) { song -> song.song.artists.joinToString("") { it.name } }
                            .thenBy(collator) { it.song.album?.title.orEmpty() },
                    )
                }

                PlaylistSongSortType.PLAY_TIME -> filteredSongs.sortedBy { it.song.song.totalPlayTime }
            }.reversed(prefs.sortDescending && prefs.sortType != PlaylistSongSortType.CUSTOM)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            val sortedSongs =
                rawPlaylistSongs.first().sortedWith(compareBy({ it.map.position }, { it.map.id }))
            database.transaction {
                sortedSongs.forEachIndexed { index, playlistSong ->
                    if (playlistSong.map.position != index) {
                        update(playlistSong.map.copy(position = index))
                    }
                }
            }
        }

        viewModelScope.launch {
            val localPlaylist = playlist.first { it != null }
            val browseId = localPlaylist?.playlist?.browseId
            if (browseId != null) {
                val page = withContext(Dispatchers.IO) {
                    YouTube.playlist(browseId).getOrNull()
                }
                val online = page?.playlist
                _onlinePlaylist.value = online
            }
        }
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 5 — the "Wahin se" badge's tap. Resuming plays the collection's own queue at the saved
// point. A Listen Together guest never starts playback from here, as upstream's tiles do not for guests.
package com.metrolist.music.saraswati.craft.resume

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.saraswati.localmedia.LocalSongsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The queue a resume plays, or null when nothing should play. Holds no Android or Compose state. */
class SpeedDialResumeActions(
    private val store: ResumeStore,
    private val localSongsOf: suspend (String) -> List<Song>,
) {
    suspend fun queueFor(
        item: YTItem,
        localPlaylist: Boolean,
        guest: Boolean,
    ): Queue? {
        if (guest) return null
        val localSongs = if (localPlaylist && item is PlaylistItem) localSongsOf(item.id) else null
        return ResumeLauncher.queueFor(item, localSongs, store.get(item.id))
    }
}

@HiltViewModel
class SpeedDialResumeViewModel
    @Inject
    constructor(
        private val store: ResumeStore,
        database: MusicDatabase,
        private val localSongs: LocalSongsRepository,
    ) : ViewModel() {
        /** Every stored resume point, keyed by collection id. */
        val points: StateFlow<Map<String, ResumePoint>> =
            store.points.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        private val actions =
            SpeedDialResumeActions(store) { playlistId ->
                // SARASWATI: Phase 5 Task 3 — with the Local files switch off, a local playlist resumes with nothing.
                if (localSongs.canServe()) database.playlistSongs(playlistId).first().map { it.song } else emptyList()
            }

        /** Plays [item] from its saved point through [playQueue]. Upstream's tile tap is unchanged. */
        fun resume(
            item: YTItem,
            localPlaylist: Boolean,
            guest: Boolean,
            playQueue: (Queue) -> Unit,
        ) {
            viewModelScope.launch {
                val queue = actions.queueFor(item, localPlaylist, guest) ?: return@launch
                playQueue(queue)
            }
        }
    }

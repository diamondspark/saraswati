/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 fix wave (Task 19 mockup conformance) — the remote screen's "Add a song" sheet: search a song,
// add it to the car's queue. Adding is a suggestion to the room, the same message the song menus send, so the car's
// approval rule (auto in a car room) decides. The search sits behind a port so the rules are unit tested.
package com.metrolist.music.saraswati.family.sangat

import androidx.lifecycle.ViewModel
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SangatCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val thumbnail: String?,
)

interface SangatSongSearch {
    suspend fun search(query: String): Result<List<SangatCandidate>>
}

class YouTubeSangatSongSearch : SangatSongSearch {
    override suspend fun search(query: String): Result<List<SangatCandidate>> =
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).map { page ->
            page.items
                .filterIsInstance<SongItem>()
                .distinctBy { it.id }
                .take(MAX_RESULTS)
                .map { song ->
                    SangatCandidate(
                        id = song.id,
                        title = song.title,
                        artist = song.artists.joinToString(", ") { it.name },
                        album = song.album?.name,
                        durationMs = song.duration?.takeIf { it > 0 }?.let { it * 1000L } ?: DEFAULT_DURATION_MS,
                        thumbnail = song.thumbnail,
                    )
                }
        }

    private companion object {
        const val MAX_RESULTS = 10
        const val DEFAULT_DURATION_MS = 180_000L
    }
}

sealed interface SangatSearchState {
    data object Idle : SangatSearchState

    data object Searching : SangatSearchState

    data object Failed : SangatSearchState

    data class Results(
        val items: List<SangatCandidate>,
    ) : SangatSearchState
}

@HiltViewModel
class SangatAddSongViewModel
    internal constructor(
        private val search: SangatSongSearch,
        private val rooms: SangatRoomPort,
        private val scope: CoroutineScope,
        private val debounceMs: Long,
    ) : ViewModel(scope) {
        @Inject
        constructor(
            search: SangatSongSearch,
            rooms: SangatRoomPort,
        ) : this(search, rooms, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), DEBOUNCE_MS)

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _state = MutableStateFlow<SangatSearchState>(SangatSearchState.Idle)
        val state: StateFlow<SangatSearchState> = _state.asStateFlow()

        private val _added = MutableStateFlow<Set<String>>(emptySet())

        /** Ids added from this sheet, so a row says "Added" instead of offering a second add. */
        val added: StateFlow<Set<String>> = _added.asStateFlow()

        private var job: Job? = null

        fun setQuery(text: String) {
            _query.value = text
            job?.cancel()
            val clean = text.trim()
            if (clean.length < MIN_QUERY) {
                _state.value = SangatSearchState.Idle
                return
            }
            _state.value = SangatSearchState.Searching
            job =
                scope.launch {
                    delay(debounceMs)
                    val result =
                        try {
                            search.search(clean)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    _state.value =
                        result.fold(
                            onSuccess = { SangatSearchState.Results(it) },
                            onFailure = { SangatSearchState.Failed },
                        )
                }
        }

        fun add(candidate: SangatCandidate) {
            if (candidate.id in _added.value) return
            rooms.suggestSong(candidate)
            _added.update { it + candidate.id }
        }

        companion object {
            const val DEBOUNCE_MS = 400L
            const val MIN_QUERY = 2
        }
    }

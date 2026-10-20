/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 21 — the Spotify import screens' logic.
//
// The file is read and parsed off the main thread; the runner blocks, so the import
// runs on the IO dispatcher too. Cancel and leaving the screen both stop the import
// after the track in progress: songs already matched stay in the playlists written so far.
package com.metrolist.music.saraswati.spotifyimport

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.min

data class PlaylistChoice(
    val index: Int,
    val name: String,
    val trackCount: Int,
    val truncated: Boolean,
    val selected: Boolean,
)

data class ImportSummary(
    val playlistIds: List<String>,
    val matched: Int,
    val unresolved: Int,
    val overLimit: Int,
    val cut: Boolean,
)

sealed interface SpotifyImportUiState {
    /** Step 1, no file yet. [error] is set when the last pick could not be read. */
    data class Pick(
        @StringRes val error: Int? = null,
    ) : SpotifyImportUiState

    data object Reading : SpotifyImportUiState

    /** Step 1, file read: the playlists it holds, all selected by default. */
    data class Chosen(
        val fileName: String,
        val playlists: List<PlaylistChoice>,
        val cut: Boolean,
    ) : SpotifyImportUiState

    /** Step 2. [done] of [total] songs looked up across the selected playlists. */
    data class Matching(
        val done: Int,
        val total: Int,
    ) : SpotifyImportUiState

    /** Step 3. Songs that did not match on their own. */
    data class Review(
        val items: List<ImportedSongResult>,
        val localLibrary: List<Song>,
        val summary: ImportSummary,
    ) : SpotifyImportUiState

    /** Step 4. [imported] songs were added; [skipped] songs were left out. */
    data class Done(
        val imported: Int,
        val skipped: Int,
        val summary: ImportSummary,
    ) : SpotifyImportUiState

    data class Failed(
        @StringRes val message: Int,
    ) : SpotifyImportUiState
}

@HiltViewModel
class SpotifyImportViewModel
    internal constructor(
        private val store: SpotifyImportStore,
        private val runnerFactory: (SpotifyImportStore) -> SpotifyImportRunner,
        private val scope: CoroutineScope,
        private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        @Inject
        constructor(database: MusicDatabase) : this(
            store = MusicDatabaseSpotifyImportStore(database),
            runnerFactory = { SpotifyImportRunner(it) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ioDispatcher = Dispatchers.IO,
        )

        private val _state = MutableStateFlow<SpotifyImportUiState>(SpotifyImportUiState.Pick())
        val state: StateFlow<SpotifyImportUiState> = _state.asStateFlow()

        private var parsed: List<ImportedPlaylist> = emptyList()
        private var chosen: SpotifyImportUiState.Chosen? = null
        private var reviewPlaylistIds: Map<String, String> = emptyMap()
        private val cancelRequested = AtomicBoolean(false)

        /** Parses the file the user picked. Nothing is written to the library here. */
        fun onFilePicked(
            fileName: String,
            open: () -> InputStream,
        ) {
            _state.value = SpotifyImportUiState.Reading
            scope.launch {
                val parsedResult: Result<List<ImportedPlaylist>> =
                    withContext(ioDispatcher) {
                        try {
                            open().use { input ->
                                if (fileName.endsWith(".csv", ignoreCase = true)) {
                                    ExportifyCsvParser.parse(input, fileName).map { listOf(it) }
                                } else {
                                    SpotifyExportParser.parse(input, fileName)
                                }
                            }
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    }
                parsedResult
                    .onSuccess { playlists ->
                        if (playlists.isEmpty()) {
                            parsed = emptyList()
                            chosen = null
                            _state.value = SpotifyImportUiState.Pick(error = R.string.spotify_import_error_unsupported)
                        } else {
                            parsed = playlists
                            val choice =
                                SpotifyImportUiState.Chosen(
                                    fileName = fileName,
                                    playlists =
                                        playlists.mapIndexed { index, playlist ->
                                            PlaylistChoice(
                                                index = index,
                                                name = playlist.name,
                                                trackCount = playlist.tracks.size,
                                                truncated = playlist.truncated,
                                                selected = playlist.tracks.isNotEmpty(),
                                            )
                                        },
                                    cut = playlists.any { it.truncated },
                                )
                            chosen = choice
                            _state.value = choice
                        }
                    }.onFailure { error ->
                        parsed = emptyList()
                        chosen = null
                        _state.value = SpotifyImportUiState.Pick(error = errorMessage(error))
                    }
            }
        }

        fun togglePlaylist(index: Int) {
            val current = chosen ?: return
            val updated =
                current.copy(
                    playlists =
                        current.playlists.map { if (it.index == index) it.copy(selected = !it.selected) else it },
                )
            chosen = updated
            _state.value = updated
        }

        fun startMatching() {
            val current = chosen ?: return
            val selected = parsed.filterIndexed { index, _ -> current.playlists.getOrNull(index)?.selected == true }
            if (selected.isEmpty()) return
            cancelRequested.set(false)
            val total = selected.sumOf { min(it.tracks.size, SpotifyImportRunner.MAX_TRACKS_PER_RUN) }
            _state.value = SpotifyImportUiState.Matching(done = 0, total = total)

            scope.launch {
                try {
                    val outcome =
                        withContext(ioDispatcher) {
                            val runner = runnerFactory(store)
                            var offset = 0
                            val reports =
                                selected.mapNotNull { playlist ->
                                    if (cancelRequested.get()) throw ImportCancelledException()
                                    val base = offset
                                    offset += min(playlist.tracks.size, SpotifyImportRunner.MAX_TRACKS_PER_RUN)
                                    if (playlist.tracks.isEmpty()) {
                                        null
                                    } else {
                                        runner.run(playlist) { done, _ ->
                                            if (cancelRequested.get()) throw ImportCancelledException()
                                            _state.value = SpotifyImportUiState.Matching(done = base + done, total = total)
                                        }
                                    }
                                }
                            buildReview(reports, current.cut)
                        }
                    _state.value = outcome
                } catch (_: ImportCancelledException) {
                    _state.value = current
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _state.value = SpotifyImportUiState.Failed(R.string.spotify_import_error_failed)
                }
            }
        }

        /** Runs off the main thread. Builds the review state, or the done state when nothing needs a look. */
        private fun buildReview(
            reports: List<ImportReport>,
            cut: Boolean,
        ): SpotifyImportUiState {
            val playlistIds = mutableMapOf<String, String>()
            val items =
                reports.flatMap { report ->
                    report.unresolved.map { result ->
                        // Song ids repeat across playlists, so the review gets a key that names its playlist.
                        val key = "${report.playlistId}:${result.originalSong.id}"
                        playlistIds[key] = report.playlistId
                        result.copy(originalSong = result.originalSong.withId(key))
                    }
                }
            reviewPlaylistIds = playlistIds
            val summary =
                ImportSummary(
                    playlistIds = reports.map { it.playlistId },
                    matched = reports.sumOf { it.matched.size },
                    unresolved = items.size,
                    overLimit = reports.sumOf { it.skippedOverLimit },
                    cut = cut,
                )
            return if (items.isEmpty()) {
                SpotifyImportUiState.Done(imported = summary.matched, skipped = 0, summary = summary)
            } else {
                SpotifyImportUiState.Review(items = items, localLibrary = store.localSongs(), summary = summary)
            }
        }

        /** Step 3 "Finish import": writes the songs the user picked; every other song is skipped. */
        fun confirmReview(reviewed: List<ImportedSongResult>) {
            val review = _state.value as? SpotifyImportUiState.Review ?: return
            scope.launch {
                val appended =
                    withContext(ioDispatcher) {
                        reviewed
                            .filter { it.isResolved }
                            .groupBy { reviewPlaylistIds[it.originalSong.id] ?: review.summary.playlistIds.first() }
                            .entries
                            .sumOf { (playlistId, results) -> commitReviewedSongs(store, playlistId, results) }
                    }
                _state.value =
                    SpotifyImportUiState.Done(
                        imported = review.summary.matched + appended,
                        skipped = review.items.size - appended,
                        summary = review.summary,
                    )
            }
        }

        fun cancel() {
            cancelRequested.set(true)
        }

        fun restart() {
            parsed = emptyList()
            chosen = null
            reviewPlaylistIds = emptyMap()
            _state.value = SpotifyImportUiState.Pick()
        }

        override fun onCleared() {
            cancelRequested.set(true)
            scope.cancel()
        }

        private fun errorMessage(error: Throwable): Int =
            when (error) {
                is ParseError.FileTooLarge, is ParseError.ZipTooLarge, is ParseError.TooManyEntries ->
                    R.string.spotify_import_error_too_large
                else -> R.string.spotify_import_error_unsupported
            }
    }

private class ImportCancelledException : RuntimeException("Spotify import cancelled")

private fun Song.withId(newId: String): Song = copy(song = song.copy(id = newId))

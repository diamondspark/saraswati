/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.spotifyimport

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Storage the runner reads from and writes to. The Room implementation is [MusicDatabaseSpotifyImportStore]. */
interface SpotifyImportStore {
    fun localSongs(): List<Song>

    fun persistSong(song: Song)

    fun createPlaylist(name: String): String

    /** Appends the songs and returns how many were written. Writes are complete when this returns. */
    fun appendSongs(
        playlistId: String,
        songIds: List<String>,
    ): Int
}

data class ImportReport(
    val playlistId: String,
    val matched: List<String>,
    val unmatched: List<ImportedTrack>,
    val unresolved: List<ImportedSongResult>,
    val skippedOverLimit: Int,
)

/**
 * Writes the songs the user picked in the review step. A YouTube pick is stored first, the same
 * way a matched YouTube song is, so the playlist entry has a row to point at. Returns how many
 * songs were actually appended.
 */
fun commitReviewedSongs(
    store: SpotifyImportStore,
    playlistId: String,
    reviewed: List<ImportedSongResult>,
): Int {
    val chosen = reviewed.filter { it.isResolved }
    chosen.forEach { result ->
        val song = result.resolvedSong ?: return@forEach
        if (result.source == ImportSource.YOUTUBE) store.persistSong(song)
    }
    val ids = chosen.mapNotNull { it.resolvedId }
    return if (ids.isEmpty()) 0 else store.appendSongs(playlistId, ids)
}

/**
 * Matches an imported playlist to songs and writes the matches into a new local playlist.
 *
 * [run] blocks the calling thread for the whole import, because YouTube searches are spaced
 * [SEARCH_SPACING_MS] apart. Call it from a background thread, never the main thread.
 */
class SpotifyImportRunner(
    private val store: SpotifyImportStore,
    private val youtubeSearch: ImportYouTubeSongSearch = YouTubeImportSongSearch,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: (Long) -> Unit = Thread::sleep,
) {
    fun run(
        playlist: ImportedPlaylist,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportReport =
        runBlocking {
            val toProcess = playlist.tracks.take(MAX_TRACKS_PER_RUN)
            val skippedOverLimit = playlist.tracks.size - toProcess.size
            val localLibrary = store.localSongs()
            val playlistId = store.createPlaylist(playlist.name)

            var lastSearchAt: Long? = null
            val spacedSearch =
                ImportYouTubeSongSearch { query ->
                    lastSearchAt?.let { previous ->
                        val wait = SEARCH_SPACING_MS - (nowMillis() - previous)
                        if (wait > 0) sleepMillis(wait)
                    }
                    lastSearchAt = nowMillis()
                    youtubeSearch.search(query)
                }
            val resolver = ImportSongResolver(youtubeSearch = spacedSearch)

            val matched = mutableListOf<String>()
            val unmatched = mutableListOf<ImportedTrack>()
            val unresolved = mutableListOf<ImportedSongResult>()
            toProcess.forEachIndexed { index, track ->
                val result =
                    resolver
                        .resolve(listOf(track.toSong(index)), localLibrary, localFirst = true)
                        .single()
                val resolved = result.resolvedSong
                val resolvedId = result.resolvedId
                val appended =
                    if (resolved != null && resolvedId != null) {
                        if (result.source == ImportSource.YOUTUBE) store.persistSong(resolved)
                        store.appendSongs(playlistId, listOf(resolvedId))
                    } else {
                        0
                    }
                if (appended > 0 && resolvedId != null) {
                    matched += resolvedId
                } else {
                    unmatched += track
                    unresolved += result
                }
                onProgress(index + 1, toProcess.size)
            }

            ImportReport(
                playlistId = playlistId,
                matched = matched,
                unmatched = unmatched,
                unresolved = unresolved,
                skippedOverLimit = skippedOverLimit,
            )
        }

    private fun ImportedTrack.toSong(index: Int): Song =
        Song(
            song =
                SongEntity(
                    id = "spotify_import_$index",
                    title = title,
                    duration = durationMs?.let { (it / 1000).toInt() } ?: -1,
                    albumName = album,
                ),
            artists =
                artists.mapIndexed { artistIndex, name ->
                    ArtistEntity(id = "spotify_import_${index}_artist_$artistIndex", name = name)
                },
        )

    companion object {
        const val MAX_TRACKS_PER_RUN = 500
        const val SEARCH_SPACING_MS = 1000L
    }
}

class MusicDatabaseSpotifyImportStore(
    private val database: MusicDatabase,
) : SpotifyImportStore {
    // Every write runs on the calling thread and returns only once it is committed. The runner
    // already runs on a background thread, so no executor hop is needed, and a later append can
    // never race an earlier insert.
    // SARASWATI: Phase 5 fix (P5-I1) — songs on this phone are never matched into an imported playlist.
    override fun localSongs(): List<Song> = runBlocking { database.allSongs().first().filterNot { it.song.isLocal } }

    override fun persistSong(song: Song) {
        database.insert(song.toMediaMetadata())
    }

    override fun createPlaylist(name: String): String {
        val playlist = PlaylistEntity(name = name)
        database.insert(playlist)
        return playlist.id
    }

    override fun appendSongs(
        playlistId: String,
        songIds: List<String>,
    ): Int =
        runBlocking {
            val playlist = database.playlist(playlistId).first() ?: return@runBlocking 0
            database.addSongsToPlaylist(playlist, songIds.map { it to null })
            songIds.size
        }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.spotifyimport

import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyImportRunnerTest {
    @Test
    fun `spaces searches at least 1 second apart`() {
        var clock = 0L
        val searchTimes = mutableListOf<Long>()
        val runner =
            SpotifyImportRunner(
                store = FakeStore(),
                youtubeSearch =
                    ImportYouTubeSongSearch {
                        searchTimes += clock
                        emptyList()
                    },
                nowMillis = { clock },
                sleepMillis = { clock += it },
            )

        runner.run(playlistOf(tracks(3))) { _, _ -> }

        assertEquals(3, searchTimes.size)
        searchTimes.zipWithNext().forEach { (previous, next) ->
            assertTrue("searches $previous and $next are too close", next - previous >= 1000)
        }
    }

    @Test
    fun `reports unmatched tracks instead of dropping them`() {
        val known = song(id = "yt-known", title = "Known Song", artist = "Known Artist")
        val runner =
            SpotifyImportRunner(
                store = FakeStore(),
                youtubeSearch = ImportYouTubeSongSearch { query -> if (query.startsWith("Known")) listOf(known) else emptyList() },
                nowMillis = { 0L },
                sleepMillis = {},
            )
        val unknown = ImportedTrack(title = "Nowhere Track", artists = listOf("Nobody"), album = null, durationMs = null)

        val report =
            runner.run(
                ImportedPlaylist(
                    name = "Mixed",
                    tracks = listOf(ImportedTrack("Known Song", listOf("Known Artist"), null, null), unknown),
                ),
            ) { _, _ -> }

        assertEquals(listOf("yt-known"), report.matched)
        assertEquals(listOf(unknown), report.unmatched)
        assertEquals(0, report.skippedOverLimit)
    }

    @Test
    fun `stops at 500 tracks and reports the rest`() {
        var searches = 0
        val runner =
            SpotifyImportRunner(
                store = FakeStore(),
                youtubeSearch =
                    ImportYouTubeSongSearch {
                        searches++
                        emptyList()
                    },
                nowMillis = { 0L },
                sleepMillis = {},
            )
        var lastTotal = -1

        val report = runner.run(playlistOf(tracks(510))) { _, total -> lastTotal = total }

        assertEquals(500, searches)
        assertEquals(500, lastTotal)
        assertEquals(500, report.unmatched.size)
        assertEquals(10, report.skippedOverLimit)
    }

    @Test
    fun `adds matched songs to a new local playlist`() {
        val known = song(id = "yt-known", title = "Known Song", artist = "Known Artist")
        val store = FakeStore()
        val runner =
            SpotifyImportRunner(
                store = store,
                youtubeSearch = ImportYouTubeSongSearch { listOf(known) },
                nowMillis = { 0L },
                sleepMillis = {},
            )

        runner.run(playlistOf(listOf(ImportedTrack("Known Song", listOf("Known Artist"), null, null)))) { _, _ -> }

        assertEquals(listOf("Road Trip"), store.createdNames)
        assertEquals(listOf(listOf("yt-known")), store.appended)
        assertEquals(listOf("yt-known"), store.persisted.map { it.song.id })
    }

    @Test
    fun `matched counts only songs the store actually appended`() {
        val known = song(id = "yt-known", title = "Known Song", artist = "Known Artist")
        val store = LaggingStore()
        val runner =
            SpotifyImportRunner(
                store = store,
                youtubeSearch = ImportYouTubeSongSearch { listOf(known) },
                nowMillis = { 0L },
                sleepMillis = {},
            )

        val report =
            runner.run(playlistOf(listOf(ImportedTrack("Known Song", listOf("Known Artist"), null, null)))) { _, _ -> }

        // The playlist write has not landed yet, so nothing was appended and nothing may be reported as matched.
        assertEquals(emptyList<String>(), report.matched)
        assertEquals(1, report.unresolved.size)
    }

    /** A store whose playlist write has not landed yet, so appends to it find no playlist. */
    private class LaggingStore : SpotifyImportStore {

        override fun localSongs(): List<Song> = emptyList()

        override fun persistSong(song: Song) {}

        override fun createPlaylist(name: String): String = "playlist-1"

        override fun appendSongs(
            playlistId: String,
            songIds: List<String>,
        ): Int = 0
    }

    private fun playlistOf(tracks: List<ImportedTrack>) = ImportedPlaylist(name = "Road Trip", tracks = tracks)

    private fun tracks(count: Int): List<ImportedTrack> =
        List(count) { index ->
            ImportedTrack(title = "Track $index", artists = listOf("Artist $index"), album = null, durationMs = null)
        }

    private fun song(
        id: String,
        title: String,
        artist: String,
    ): Song =
        Song(
            song = SongEntity(id = id, title = title),
            artists = listOf(ArtistEntity(id = "$id-artist", name = artist)),
        )

    private class FakeStore : SpotifyImportStore {
        val createdNames = mutableListOf<String>()
        val appended = mutableListOf<List<String>>()
        val persisted = mutableListOf<Song>()

        override fun localSongs(): List<Song> = emptyList()

        override fun persistSong(song: Song) {
            persisted += song
        }

        override fun createPlaylist(name: String): String {
            createdNames += name
            return "playlist-1"
        }

        override fun appendSongs(
            playlistId: String,
            songIds: List<String>,
        ): Int {
            appended += songIds
            return songIds.size
        }
    }
}

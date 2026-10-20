/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.spotifyimport

import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpotifyImportViewModelTest {
    private lateinit var scope: CoroutineScope
    private val store = RecordingStore()
    private val known = song(id = "yt-known", title = "Known Song", artist = "Known Artist")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a picked zip with three playlists offers all three`() {
        val vm = newVm()
        val zip =
            zipOf(
                "Playlist1.json" to playlistJson("Road Trip", "Known Song"),
                "Playlist2.json" to playlistJson("Morning", "Known Song"),
                "Playlist3.json" to playlistJson("Old Favourites", "Known Song"),
            )

        vm.onFilePicked("my_spotify_data.zip") { ByteArrayInputStream(zip) }

        val chosen = vm.state.value as SpotifyImportUiState.Chosen
        assertEquals(listOf("Road Trip", "Morning", "Old Favourites"), chosen.playlists.map { it.name })
        assertTrue(chosen.playlists.all { it.selected })
    }

    @Test
    fun `progress reaches total`() {
        // StateFlow conflates under Unconfined, so the search lambda reads the state while the run is in flight.
        lateinit var vm: SpotifyImportViewModel
        val observed = mutableListOf<SpotifyImportUiState.Matching>()
        val search =
            ImportYouTubeSongSearch { query ->
                (vm.state.value as? SpotifyImportUiState.Matching)?.let { observed += it }
                if (query.contains("Known")) listOf(known) else emptyList()
            }
        vm = newVm(search)
        vm.onFilePicked("Playlist1.json") { ByteArrayInputStream(playlistJson("Mix", "Known Song", "Known Song").toByteArray()) }

        vm.startMatching()

        assertEquals(listOf(0 to 2, 1 to 2), observed.map { it.done to it.total })
        assertEquals(2, (vm.state.value as SpotifyImportUiState.Done).imported)
    }

    @Test
    fun `unmatched tracks go to review`() {
        val vm = newVm()
        vm.onFilePicked("Playlist1.json") { ByteArrayInputStream(playlistJson("Mixed", "Known Song", "Nowhere Track").toByteArray()) }

        vm.startMatching()

        val review = vm.state.value as SpotifyImportUiState.Review
        assertEquals(listOf("Nowhere Track"), review.items.map { it.originalSong.title })
        assertEquals(listOf("Mixed"), store.createdNames)

        vm.confirmReview(emptyList())

        val done = vm.state.value as SpotifyImportUiState.Done
        assertEquals(1, done.imported)
        assertEquals(1, done.skipped)
    }

    @Test
    fun `a parse error shows a message and imports nothing`() {
        val vm = newVm()

        vm.onFilePicked("notes.txt") { ByteArrayInputStream("not a spotify export".toByteArray()) }

        val pick = vm.state.value as SpotifyImportUiState.Pick
        assertNotNull(pick.error)
        assertEquals(emptyList<String>(), store.createdNames)
        assertEquals(emptyList<List<String>>(), store.appended)
    }

    @Test
    fun `a list over 10,000 tracks is flagged as cut`() {
        val vm = newVm()
        val titles = List(SpotifyImportLimits.MAX_TRACKS + 1) { "Track $it" }

        vm.onFilePicked("Playlist1.json") { ByteArrayInputStream(playlistJson("Long", *titles.toTypedArray()).toByteArray()) }

        val chosen = vm.state.value as SpotifyImportUiState.Chosen
        assertTrue(chosen.cut)
        assertEquals(SpotifyImportLimits.MAX_TRACKS, chosen.playlists.single().trackCount)
    }

    private fun newVm(
        search: ImportYouTubeSongSearch = ImportYouTubeSongSearch { query -> if (query.contains("Known")) listOf(known) else emptyList() },
    ): SpotifyImportViewModel {
        return SpotifyImportViewModel(
            store = store,
            runnerFactory = { s ->
                SpotifyImportRunner(store = s, youtubeSearch = search, nowMillis = { 0L }, sleepMillis = {})
            },
            scope = scope,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun playlistJson(
        name: String,
        vararg titles: String,
    ): String {
        val items =
            titles.joinToString(",") { title ->
                """{"track":{"trackName":"$title","artistName":"Known Artist","albumName":"Album"}}"""
            }
        return """{"playlists":[{"name":"$name","items":[$items]}]}"""
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
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

    private class RecordingStore : SpotifyImportStore {
        val createdNames = mutableListOf<String>()
        val appended = mutableListOf<List<String>>()

        override fun localSongs(): List<Song> = emptyList()

        override fun persistSong(song: Song) {}

        override fun createPlaylist(name: String): String {
            createdNames += name
            return "playlist-${createdNames.size}"
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

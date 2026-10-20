package com.metrolist.music.saraswati.localmedia

import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

internal class FakeLocalSongStore : LocalSongStore {
    val songs = linkedMapOf<String, SongEntity>()
    val artists = mutableMapOf<String, List<String>>()
    var upserts = 0

    override suspend fun localSongStamps(): Map<String, LocalDateTime?> = songs.mapValues { it.value.dateModified }

    override suspend fun upsert(
        song: SongEntity,
        artistNames: List<String>,
    ) {
        upserts++
        songs[song.id] = song
        artists[song.id] = artistNames
    }

    override suspend fun deleteLocal(ids: List<String>) {
        ids.forEach {
            songs.remove(it)
            artists.remove(it)
        }
    }
}

class LocalLibrarySyncTest {
    private val store = FakeLocalSongStore()
    private val sync = LocalLibrarySync(store)

    private fun row(
        id: Long = 42,
        title: String? = "Song",
        artist: String? = "Artist",
        album: String? = "Album",
        displayName: String? = "file.mp3",
        modified: Long = 100,
        path: String? = "Music/",
        durationMs: Long = 215_000,
    ) = MediaStoreAudioRow(id, title, artist, album, 7, durationMs, "audio/mpeg", path, displayName, modified, false)

    @Test
    fun `inserts local id rows with isLocal true and inLibrary null`() =
        runBlocking {
            val report = sync.sync(listOf(row()))
            val song = store.songs.getValue("local:42")
            assertEquals(LocalSyncReport(added = 1, updated = 0, removed = 0), report)
            assertTrue(song.isLocal)
            assertNull(song.inLibrary)
            assertEquals("Song", song.title)
            assertEquals(215, song.duration)
            assertEquals("content://media/external/audio/albums/7", song.thumbnailUrl)
            assertEquals("Album", song.albumName)
        }

    @Test
    fun `title falls back to the file name without extension`() =
        runBlocking {
            sync.sync(listOf(row(title = null, displayName = "My Song v2.final.flac"), row(id = 43, title = "  ", displayName = "NoDot")))
            assertEquals("My Song v2.final", store.songs.getValue("local:42").title)
            assertEquals("NoDot", store.songs.getValue("local:43").title)
        }

    @Test
    fun `unknown artist and album are dropped`() =
        runBlocking {
            sync.sync(listOf(row(artist = "<unknown>", album = "<unknown>")))
            assertEquals(emptyList<String>(), store.artists.getValue("local:42"))
            assertNull(store.songs.getValue("local:42").albumName)
        }

    @Test
    fun `artists split on semicolon and comma`() =
        runBlocking {
            sync.sync(listOf(row(artist = "Lata Mangeshkar; Mohammed Rafi, Asha Bhosle,<unknown>")))
            assertEquals(listOf("Lata Mangeshkar", "Mohammed Rafi", "Asha Bhosle"), store.artists.getValue("local:42"))
        }

    @Test
    fun `a changed dateModified updates the row and an unchanged one is left alone`() =
        runBlocking {
            sync.sync(listOf(row(modified = 100)))
            assertEquals(1, store.upserts)
            assertEquals(LocalSyncReport(0, 0, 0), sync.sync(listOf(row(modified = 100))))
            assertEquals(1, store.upserts)
            val report = sync.sync(listOf(row(modified = 200, title = "Renamed")))
            assertEquals(LocalSyncReport(added = 0, updated = 1, removed = 0), report)
            assertEquals("Renamed", store.songs.getValue("local:42").title)
        }

    @Test
    fun `a vanished file's row is deleted`() =
        runBlocking {
            sync.sync(listOf(row(id = 1), row(id = 2)))
            val report = sync.sync(listOf(row(id = 1)))
            assertEquals(1, report.removed)
            assertEquals(setOf("local:1"), store.songs.keys)
        }

    @Test
    fun `a row that now fails the filter is deleted`() =
        runBlocking {
            sync.sync(listOf(row(id = 1)))
            val report = sync.sync(listOf(row(id = 1, path = "Recordings/Call/")))
            assertEquals(1, report.removed)
            assertTrue(store.songs.isEmpty())
        }

    @Test
    fun `rows that fail the filter are never inserted`() =
        runBlocking {
            val report = sync.sync(listOf(row(durationMs = 5_000), row(id = 2, path = "Android/media/x/")))
            assertEquals(LocalSyncReport(0, 0, 0), report)
            assertTrue(store.songs.isEmpty())
        }
}

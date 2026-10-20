package com.metrolist.music.saraswati.localmedia

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalSongsRepositoryTest {
    private lateinit var context: Context
    private lateinit var internal: InternalDatabase
    private lateinit var database: MusicDatabase
    private lateinit var store: FakeLocalSongStore
    private var access = LocalAccess.GRANTED
    private var reads = 0
    private var failReads = false
    private var rows = listOf(row(1))

    private fun row(id: Long) =
        MediaStoreAudioRow(id, "Song $id", "Artist", "Album", 7, 200_000, "audio/mpeg", "Music/", "s$id.mp3", 100, false)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        database = MusicDatabase(internal)
        store = FakeLocalSongStore()
    }

    @After
    fun tearDown() {
        internal.close()
    }

    private fun repository(songsFlow: kotlinx.coroutines.flow.Flow<List<com.metrolist.music.db.entities.Song>> = flowOf(emptyList()), countFlow: kotlinx.coroutines.flow.Flow<Int> = flowOf(0)): LocalSongsRepository {
        val prefs = PreferenceDataStoreFactory.create(produceFile = { File.createTempFile("local", ".preferences_pb", context.cacheDir).also { it.delete() } })
        return LocalSongsRepository(
            allSongs = songsFlow,
            allCount = countFlow,
            access = { access },
            reader =
                MediaStoreAudioReader {
                    reads++
                    if (failReads) error("MediaStore down")
                    rows
                },
            sync = LocalLibrarySync(store),
            dataStore = prefs,
        )
    }

    @Test
    fun `never reads MediaStore without the permission`() =
        runBlocking {
            val repo = repository()
            LocalAccess.entries.filter { it != LocalAccess.GRANTED }.forEach { denied ->
                access = denied
                val result = repo.syncNow()
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is LocalAccessException)
                repo.syncIfDue(Instant.ofEpochSecond(1_000_000))
            }
            assertEquals(0, reads)
            assertTrue(store.songs.isEmpty())
        }

    @Test
    fun `syncIfDue runs at most every 6 hours`() =
        runBlocking {
            val repo = repository()
            val t0 = Instant.ofEpochSecond(1_000_000)
            repo.syncIfDue(t0)
            assertEquals(1, reads)
            repo.syncIfDue(t0.plusSeconds(5 * 3600 + 3599))
            assertEquals(1, reads)
            repo.syncIfDue(t0.plusSeconds(6 * 3600))
            assertEquals(2, reads)
        }

    @Test
    fun `a reader failure keeps existing rows`() =
        runBlocking {
            val repo = repository()
            assertTrue(repo.syncNow().isSuccess)
            assertEquals(setOf("local:1"), store.songs.keys)
            failReads = true
            rows = emptyList()
            val result = repo.syncNow()
            assertTrue(result.isFailure)
            assertEquals(setOf("local:1"), store.songs.keys)
            // a failed scheduled scan is not retried at once
            repo.syncIfDue(Instant.ofEpochSecond(2_000_000))
            val after = reads
            repo.syncIfDue(Instant.ofEpochSecond(2_000_060))
            assertEquals(after, reads)
        }

    @Test
    fun `the Room store keeps local songs out of the library and removes their maps with them`() =
        runBlocking {
            val room = RoomLocalSongStore(database)
            val sync = LocalLibrarySync(room)
            sync.sync(listOf(row(2).copy(artist = "Lata; Rafi"), row(1).copy(title = "Alpha")))
            val songs = database.localSongsByTitle().first()
            assertEquals(listOf("Alpha", "Song 2"), songs.map { it.song.title })
            assertTrue(songs.all { it.song.isLocal && it.song.inLibrary == null })
            assertEquals(listOf("Lata", "Rafi"), songs.last().artists.map { it.name }.sorted())
            assertEquals(2, database.localSongCount().first())
            assertEquals(listOf("Alpha"), database.localSongsPage(1, 0).map { it.song.title })
            assertEquals(listOf("Song 2"), database.localSongsPage(1, 1).map { it.song.title })
            assertEquals(setOf("local:1", "local:2"), room.localSongIds().toSet())
            assertNotNull(database.artistByName("Lata"))

            // an existing artist of that name is reused rather than duplicated
            val before = database.artistByName("Lata")!!.id
            sync.sync(listOf(row(2).copy(artist = "Lata", dateModifiedSec = 999), row(1)))
            assertEquals(before, database.artistByName("Lata")!!.id)

            sync.sync(listOf(row(1)))
            assertNull(database.songEntity("local:2"))
            internal.query("SELECT COUNT(*) FROM song_artist_map WHERE songId = 'local:2'", null).use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            assertFalse(database.localSongIds().contains("local:2"))
        }

    @Test
    fun `the Local files switch off means no scan, no read and hidden songs`() =
        runBlocking {
            val room = RoomLocalSongStore(database)
            LocalLibrarySync(room).sync(listOf(row(1), row(2)))
            val repo = repository(database.localSongsByTitle(), database.localSongCount())
            assertEquals(2, repo.count.first())
            assertEquals(2, repo.songs.first().size)

            repo.setEnabled(false)
            assertEquals(0, repo.count.first())
            assertTrue(repo.songs.first().isEmpty())
            val result = repo.syncNow()
            assertTrue(result.exceptionOrNull() is LocalFilesOffException)
            repo.syncIfDue(Instant.ofEpochSecond(9_000_000))
            assertEquals(0, reads)
            // switching off hides the songs; it does not delete them
            assertEquals(2, database.localSongCount().first())

            repo.setEnabled(true)
            assertEquals(2, repo.count.first())
            assertTrue(repo.syncNow().isSuccess)
            assertEquals(1, reads)
        }

    @Test
    fun `without access the songs already found are hidden too`() =
        runBlocking {
            LocalLibrarySync(RoomLocalSongStore(database)).sync(listOf(row(1)))
            val repo = repository(database.localSongsByTitle(), database.localSongCount())
            assertEquals(1, repo.count.first())
            access = LocalAccess.NOT_GRANTED
            repo.setEnabled(true)
            assertEquals(0, repo.count.first())
        }
}

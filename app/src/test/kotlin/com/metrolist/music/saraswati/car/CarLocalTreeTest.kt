/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 6 — the car's Downloaded and On this phone rows, and what their folders and taps queue.
package com.metrolist.music.saraswati.car

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.R
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.saraswati.localmedia.LocalAccess
import com.metrolist.music.saraswati.localmedia.LocalLibrarySync
import com.metrolist.music.saraswati.localmedia.LocalSongStore
import com.metrolist.music.saraswati.localmedia.LocalSongsRepository
import com.metrolist.music.saraswati.localmedia.MediaStoreAudioReader
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarLocalTreeTest {
    private val resources =
        CarResources(
            string = {
                when (it) {
                    R.string.car_local_allow -> "Allow on your phone"
                    R.string.car_local_title -> "On this phone"
                    R.string.car_downloaded_title -> "Downloaded"
                    else -> "string#$it"
                }
            },
            iconUri = { Uri.parse("icon://$it") },
            songCount = { "$it songs" },
        )

    private val downloadedId = "${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}"

    private fun song(id: String, title: String) = Song(SongEntity(id = id, title = title), artists = emptyList())

    private val songs = listOf(song("local:3", "Zebra"), song("local:1", "apple"), song("local:2", "Mango"))

    @Test
    fun `Library ends with Downloaded then On this phone`() {
        val rows = CarLocalTree.libraryRows(resources, LocalAccess.GRANTED, localCount = 3, downloadedCount = 2)
        assertEquals(listOf("playlist/$downloadedId", com.metrolist.music.playback.MusicService.CAR_LOCAL), rows.map { it.mediaId })
        assertTrue(rows.all { it.mediaMetadata.isBrowsable == true })
        assertEquals("3 songs", rows[1].mediaMetadata.subtitle.toString())
    }

    @Test
    fun `On this phone is hidden below Android 13`() {
        val rows = CarLocalTree.libraryRows(resources, LocalAccess.UNSUPPORTED, localCount = 3, downloadedCount = 2)
        assertEquals(listOf("playlist/$downloadedId"), rows.map { it.mediaId })
    }

    @Test
    fun `hidden when granted with no songs`() {
        val rows = CarLocalTree.libraryRows(resources, LocalAccess.GRANTED, localCount = 0, downloadedCount = 0)
        assertEquals(listOf("playlist/$downloadedId"), rows.map { it.mediaId })
    }

    @Test
    fun `On this phone asks for permission in its subtitle when not granted`() {
        val rows = CarLocalTree.libraryRows(resources, LocalAccess.NOT_GRANTED, localCount = 0, downloadedCount = 0)
        assertEquals("Allow on your phone", rows.last().mediaMetadata.subtitle.toString())
    }

    @Test
    fun `without permission the folder holds one disabled Allow on your phone row`() {
        val children = CarLocalTree.children(resources, LocalAccess.NOT_GRANTED, songs, includeShuffle = true)
        assertEquals(1, children.size)
        assertEquals("Allow on your phone", children.single().mediaMetadata.title.toString())
        assertFalse(children.single().mediaMetadata.isPlayable == true)
    }

    @Test
    fun `children come from Room only`() =
        runBlocking {
            var reads = 0
            val prefs =
                PreferenceDataStoreFactory.create(
                    produceFile = { File.createTempFile("car-local", ".preferences_pb", ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir).also { it.delete() } },
                )
            val repository =
                LocalSongsRepository(
                    allSongs = flowOf(songs),
                    allCount = flowOf(songs.size),
                    access = { LocalAccess.GRANTED },
                    reader = MediaStoreAudioReader { reads++; error("MediaStore must not be read for the car") },
                    sync = LocalLibrarySync(NoStore),
                    dataStore = prefs,
                )
            val children = CarLocalTree.children(resources, LocalAccess.GRANTED, repository.songs.first(), includeShuffle = true)
            assertEquals(0, reads)
            assertEquals(listOf("car_local/__shuffle__", "car_local/local:3", "car_local/local:1", "car_local/local:2"), children.map { it.mediaId })
        }

    @Test
    fun `selecting a local song queues all local songs from it`() {
        val queue = CarLocalTree.queueFrom(songs, "local:2")!!
        assertEquals(listOf("apple", "Mango", "Zebra"), queue.songs.map { it.song.title })
        assertEquals(1, queue.startIndex)
        assertEquals(null, CarLocalTree.queueFrom(songs, "local:99"))
    }

    @Test
    fun `shuffle queues all local songs shuffled`() {
        val shuffled = CarLocalTree.shuffleQueue(songs, Random(7))
        assertEquals(songs.map { it.id }.sorted(), shuffled.map { it.id }.sorted())
        assertNotEquals(songs.map { it.id }, shuffled.map { it.id })
    }

    private object NoStore : LocalSongStore {
        override suspend fun localSongStamps(): Map<String, LocalDateTime?> = emptyMap()

        override suspend fun upsert(
            song: SongEntity,
            artistNames: List<String>,
        ) = Unit

        override suspend fun deleteLocal(ids: List<String>) = Unit
    }
}



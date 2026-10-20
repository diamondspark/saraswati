/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.resume

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumeRecorderTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private var now = 1_000_000L

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    // Recording launches the write on the store's scope, so each check waits for the stored value to settle.
    private fun awaitStored(
        store: ResumeStore,
        sourceId: String,
    ): ResumePoint? =
        blocking {
            delay(50)
            store.points.first()[sourceId]
        }

    private val album = ResumableQueue("album-1", ListQueue(items = listOf(MediaItem.Builder().setMediaId("a").build())), null)

    private class PlainQueue : Queue {
        override val preloadItem: MediaMetadata? = null

        override suspend fun getInitialStatus() = Queue.Status(null, emptyList(), 0, 0L)

        override fun hasNextPage() = false

        override suspend fun nextPage(): List<MediaItem> = emptyList()
    }

    private fun store() = ResumeStore(dataStore, { now }, scope).also { blocking { it.awaitHydrated() } }

    @Test
    fun `a queue started from a speed-dial album records the song and position on pause`() {
        val store = store()
        val recorder = ResumeRecorder(store, { now }, scope)

        recorder.onProgress(album, index = 3, mediaId = "song-3", count = 10, positionMs = 42_000L, durationMs = 200_000L, force = true)

        val saved = awaitStored(store, "album-1")
        assertEquals("song-3", saved?.mediaId)
        assertEquals(3, saved?.index)
        assertEquals(42_000L, saved?.positionMs)
    }

    @Test
    fun `a non-resumable queue records nothing`() {
        val store = store()
        val recorder = ResumeRecorder(store, { now }, scope)

        recorder.onProgress(PlainQueue(), 1, "song-1", 5, 10_000L, 200_000L, force = true)
        recorder.onProgress(null, 1, "song-1", 5, 10_000L, 200_000L, force = true)

        assertNull(awaitStored(store, "album-1"))
        assertEquals(emptyMap<String, ResumePoint>(), blocking { store.points.first() })
    }

    @Test
    fun `finishing the last song clears the resume point`() {
        val store = store()
        blocking { store.save(ResumePoint("album-1", "song-9", 9, 5_000L, now)) }
        val recorder = ResumeRecorder(store, { now }, scope)

        recorder.onProgress(album, index = 9, mediaId = "song-9", count = 10, positionMs = 195_000L, durationMs = 200_000L, force = true)

        assertNull(awaitStored(store, "album-1"))
    }

    @Test
    fun `saves while playing are at most one per 10 seconds`() {
        val store = store()
        val recorder = ResumeRecorder(store, { now }, scope)

        recorder.onProgress(album, 0, "song-0", 10, 1_000L, 200_000L, force = false)
        now += 4_000
        recorder.onProgress(album, 0, "song-0", 10, 5_000L, 200_000L, force = false)
        assertEquals(1_000L, awaitStored(store, "album-1")?.positionMs)

        now += 6_000
        recorder.onProgress(album, 0, "song-0", 10, 11_000L, 200_000L, force = false)
        assertEquals(11_000L, awaitStored(store, "album-1")?.positionMs)
    }
}

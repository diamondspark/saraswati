/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.resume

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Exercises SpeedDialResumeActions, which holds the ViewModel's logic, so it runs without the Android main dispatcher.
@RunWith(RobolectricTestRunner::class)
class SpeedDialResumeViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private val now = 1_000_000L

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

    private val album =
        AlbumItem(
            browseId = "MPREb_album",
            playlistId = "OLAK_album",
            title = "Album",
            artists = null,
            thumbnail = "thumb",
        )

    private fun playlist(id: String) =
        PlaylistItem(
            id = id,
            title = "Playlist",
            author = null,
            songCountText = null,
            thumbnail = null,
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )

    private fun localSong(id: String) = Song(song = SongEntity(id = id, title = "Song $id"), artists = emptyList())

    @Test
    fun `resume plays a ResumableQueue at the saved point`() {
        val store = ResumeStore(dataStore, { now }, scope)
        blocking { store.save(ResumePoint("MPREb_album", "vid-2", 2, 73_000L, now)) }
        blocking { store.awaitHydrated() }
        val actions = SpeedDialResumeActions(store) { emptyList() }

        val queue = blocking { actions.queueFor(album, localPlaylist = false, guest = false) }

        assertTrue(queue is ResumableQueue)
        assertEquals("MPREb_album", (queue as ResumableQueue).sourceId)
    }

    @Test
    fun `a Listen Together guest cannot resume`() {
        val store = ResumeStore(dataStore, { now }, scope)
        val actions = SpeedDialResumeActions(store) { emptyList() }

        assertNull(blocking { actions.queueFor(album, localPlaylist = false, guest = true) })
    }

    @Test
    fun `a local playlist resumes from its database songs`() {
        val store = ResumeStore(dataStore, { now }, scope)
        blocking { store.awaitHydrated() }
        val actions = SpeedDialResumeActions(store) { listOf(localSong("local:1"), localSong("local:2")) }

        val queue = blocking { actions.queueFor(playlist("LOCAL_PL"), localPlaylist = true, guest = false) }
        val status = blocking { queue!!.getInitialStatus() }

        assertEquals(listOf("local:1", "local:2"), status.items.map { it.mediaId })
    }

    @Test
    fun `no badge without an offered point`() {
        assertFalse(ResumePolicy.offer(null, now))
        assertFalse(ResumePolicy.offer(ResumePoint("MPREb_album", "vid-0", 0, 10_000L, now), now))
    }
}

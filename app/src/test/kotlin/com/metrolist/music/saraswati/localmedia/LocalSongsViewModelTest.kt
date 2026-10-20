/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 14 — the On this phone view model against a real file-backed DataStore: the permission
// flow (nothing requested until Allow, one scan on a grant, open-settings after two refusals), the switch, and the
// play and shuffle queues.
package com.metrolist.music.saraswati.localmedia

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalSongsViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: LocalSongsRepository
    private var access = LocalAccess.NOT_GRANTED
    private var reads = 0
    private val rows = MutableStateFlow<List<Song>>(emptyList())

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        repository =
            LocalSongsRepository(
                allSongs = rows,
                allCount = rows.map { it.size },
                access = { access },
                reader = MediaStoreAudioReader {
                    reads++
                    emptyList()
                },
                sync = LocalLibrarySync(FakeLocalSongStore()),
                dataStore = dataStore,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun song(id: Long) =
        Song(SongEntity(id = LocalSongIds.of(id), title = "Song $id", duration = 200, isLocal = true), artists = emptyList())

    // The view model runs on Robolectric's paused main looper, so the test thread must idle that looper while it
    // waits; blocking the thread with runBlocking would starve the state flow that the view model publishes.
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    private fun awaitState(
        vm: LocalSongsViewModel,
        expected: LocalSongsState,
    ): LocalSongsState {
        scope.launch { vm.state.collect {} }
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.state.value != expected && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertEquals(expected, vm.state.value)
        return expected
    }

    @Test
    fun `below Android 13 shows Unsupported and never requests`() {
        access = LocalAccess.UNSUPPORTED
        val vm = LocalSongsViewModel(repository)

        awaitState(vm, LocalSongsState.Unsupported)
        vm.onResume()
        vm.refresh()

        assertEquals(0, reads)
    }

    @Test
    fun `nothing is requested until allow`() {
        val vm = LocalSongsViewModel(repository)

        awaitState(vm, LocalSongsState.NeedsPermission(0))
        vm.onResume()
        settle(200)

        assertEquals(0, reads)
        assertEquals(LocalSongsState.NeedsPermission(0), vm.state.value)
    }

    @Test
    fun `a grant triggers one sync`() {
        val vm = LocalSongsViewModel(repository)
        awaitState(vm, LocalSongsState.NeedsPermission(0))

        access = LocalAccess.GRANTED
        vm.onPermissionResult(granted = true)
        awaitState(vm, LocalSongsState.Empty)
        settle(200)

        assertEquals(1, reads)
    }

    @Test
    fun `two denials switch to open-settings`() {
        val vm = LocalSongsViewModel(repository)
        awaitState(vm, LocalSongsState.NeedsPermission(0))

        vm.onPermissionResult(granted = false)
        awaitState(vm, LocalSongsState.NeedsPermission(1))
        vm.onPermissionResult(granted = false)
        awaitState(vm, LocalSongsState.NeedsPermission(2))

        assertEquals(0, reads)
    }

    @Test
    fun `the switch off shows Off and reads nothing`() {
        access = LocalAccess.GRANTED
        rows.value = listOf(song(1))
        runBlocking { repository.setEnabled(false) }
        val vm = LocalSongsViewModel(repository)

        awaitState(vm, LocalSongsState.Off)
        vm.onResume()
        vm.refresh()
        settle(200)

        assertEquals(0, reads)
        assertEquals(LocalSongsState.Off, vm.state.value)
    }

    @Test
    fun `play starts a ListQueue from the tapped index`() {
        access = LocalAccess.GRANTED
        rows.value = listOf(song(1), song(2), song(3))
        val vm = LocalSongsViewModel(repository)
        awaitState(vm, LocalSongsState.Songs(rows.value))

        var started: Queue? = null
        vm.play(1) { started = it }

        val queue = started as ListQueue
        assertEquals(1, queue.startIndex)
        assertEquals(listOf("local:1", "local:2", "local:3"), queue.items.map { it.mediaId })
    }

    @Test
    fun `shuffle starts shuffled`() {
        access = LocalAccess.GRANTED
        val all = (1L..20L).map { song(it) }
        rows.value = all
        val vm = LocalSongsViewModel(repository)
        awaitState(vm, LocalSongsState.Songs(all))

        var started: Queue? = null
        vm.shuffle { started = it }

        val queue = started as ListQueue
        val ids = queue.items.map { it.mediaId }
        assertEquals(0, queue.startIndex)
        assertEquals(all.map { it.song.id }.toSet(), ids.toSet())
        assertNotEquals(all.map { it.song.id }, ids)
    }
}

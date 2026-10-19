/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — the weekly top-songs playlist against a fake YouTube account, a fake hub and a real
// file-backed DataStore.
package com.metrolist.music.saraswati.family.parivaar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.ParivaarPlaylistIdKey
import com.metrolist.music.constants.ParivaarTopSongsSyncedAtKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Duration
import java.time.Instant

class TopSongsPlaylistSyncTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private val port = FakePort()
    private val hub = FakeHub()
    private val identity = FakeIdentity()
    private val source = FakeTopSongs()
    private val pauses = mutableListOf<Long>()
    private val t0 = Instant.parse("2026-10-19T08:00:00Z")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // A new instance each time: a launch is a new process, with no memory of the last one.
    private fun sync(enabled: Boolean = true) =
        TopSongsPlaylistSync(port, hub, identity, source, dataStore, enabled = { enabled }, pause = { pauses += it })

    private fun songs(count: Int) = (1..count).map { RankedSong(track("song%02d".format(it)), isLocal = false) }

    private fun stored() = runBlocking { dataStore.data.first() }

    @Test
    fun `first sync creates one unlisted playlist titled Saraswati Mummy top songs, adds the top 30 and registers it with the hub`() =
        runBlocking {
            source.songs = songs(45)

            val result = sync().syncIfDue(t0, "Mummy")

            assertTrue(result.isSuccess)
            assertEquals(listOf("Saraswati · Mummy top songs"), port.createdTitles)
            assertEquals(1, port.calls.count { it == "create" })
            val id = port.playlists.keys.single()
            assertEquals((1..30).map { "song%02d".format(it) }, port.ids(id))
            assertEquals(listOf(Triple("session-token", id, "Mummy")), hub.registered)
            assertEquals(id, stored()[ParivaarPlaylistIdKey])
            assertEquals(t0.toEpochMilli(), stored()[ParivaarTopSongsSyncedAtKey])
        }

    @Test
    fun `the last 90 days are asked for, with room to drop local files`() =
        runBlocking {
            source.songs = songs(3)

            sync().syncIfDue(t0, "Mummy")

            assertEquals(t0.minus(Duration.ofDays(90)), source.lastSince)
            assertTrue(source.lastLimit!! > 30)
        }

    @Test
    fun `later syncs add new and remove dropped songs without recreating`() =
        runBlocking {
            source.songs = songs(30)
            sync().syncIfDue(t0, "Mummy")
            val id = port.playlists.keys.single()
            port.calls.clear()
            hub.registered.clear()
            // song01 and song02 dropped out of the top 30; two new ones came in at the bottom.
            source.songs = songs(32).drop(2)

            val result = sync().syncIfDue(t0.plus(Duration.ofDays(7)), "Mummy")

            assertTrue(result.isSuccess)
            assertEquals(listOf("entries:$id", "remove:song01", "remove:song02", "add:song31", "add:song32"), port.calls)
            assertEquals(1, port.playlists.size)
            assertEquals((3..32).map { "song%02d".format(it) }.toSet(), port.ids(id).toSet())
            assertEquals(1, hub.registered.size)
        }

    @Test
    fun `a week costs at most 30 adds and 30 removes`() =
        runBlocking {
            source.songs = songs(30)
            sync().syncIfDue(t0, "Mummy")
            val id = port.playlists.keys.single()
            port.calls.clear()
            source.songs = songs(60).drop(30)

            sync().syncIfDue(t0.plus(Duration.ofDays(8)), "Mummy")

            assertEquals(30, port.calls.count { it.startsWith("remove:") })
            assertEquals(30, port.calls.count { it.startsWith("add:") })
            assertEquals((31..60).map { "song%02d".format(it) }, port.ids(id))
        }

    @Test
    fun `edit calls are spaced 500 ms apart`() =
        runBlocking {
            source.songs = songs(4)

            sync().syncIfDue(t0, "Mummy")

            // The create and four adds are five edit calls: a gap before each but the first.
            assertEquals(listOf(500L, 500L, 500L, 500L), pauses)
            assertEquals(500L, TopSongsPlaylistSync.EDIT_CALL_SPACING_MS)
        }

    @Test
    fun `local songs are never added`() =
        runBlocking {
            source.songs =
                listOf(RankedSong(track("local-1"), isLocal = true)) + songs(5) + RankedSong(track("local-2"), isLocal = true)

            sync().syncIfDue(t0, "Mummy")

            val id = port.playlists.keys.single()
            assertEquals((1..5).map { "song%02d".format(it) }, port.ids(id))
        }

    @Test
    fun `a song twice in the list is added once`() =
        runBlocking {
            source.songs = songs(2) + songs(2)

            sync().syncIfDue(t0, "Mummy")

            assertEquals(listOf("song01", "song02"), port.ids(port.playlists.keys.single()))
        }

    @Test
    fun `nothing happens before 7 days`() =
        runBlocking {
            source.songs = songs(5)
            sync().syncIfDue(t0, "Mummy")
            port.calls.clear()
            hub.registered.clear()
            source.songs = songs(9)

            val result = sync().syncIfDue(t0.plus(Duration.ofDays(7)).minusSeconds(1), "Mummy")

            assertTrue(result.isSuccess)
            assertEquals(emptyList<String>(), port.calls)
            assertEquals(emptyList<Any>(), hub.registered)
        }

    @Test
    fun `a playlist deleted on YouTube is recreated once`() =
        runBlocking {
            source.songs = songs(3)
            sync().syncIfDue(t0, "Mummy")
            val first = port.playlists.keys.single()
            port.playlists.remove(first)

            sync().syncIfDue(t0.plus(Duration.ofDays(7)), "Mummy")
            val second = port.playlists.keys.single()
            sync().syncIfDue(t0.plus(Duration.ofDays(14)), "Mummy")

            assertTrue(first != second)
            assertEquals(2, port.calls.count { it == "create" })
            assertEquals(second, stored()[ParivaarPlaylistIdKey])
            assertEquals(listOf("song01", "song02", "song03"), port.ids(second))
            assertEquals(second, hub.registered.last().second)
        }

    @Test
    fun `a failed read that is not a deletion does not recreate the playlist`() =
        runBlocking {
            source.songs = songs(3)
            sync().syncIfDue(t0, "Mummy")
            val id = port.playlists.keys.single()
            port.failEntries[id] = IOException("offline")

            val result = sync().syncIfDue(t0.plus(Duration.ofDays(7)), "Mummy")

            assertTrue(result.isFailure)
            assertEquals(1, port.calls.count { it == "create" })
            assertEquals(id, stored()[ParivaarPlaylistIdKey])
        }

    @Test
    fun `a hub failure keeps the playlist id and retries registration next launch`() =
        runBlocking {
            source.songs = songs(3)
            hub.failRegister = IOException("hub down")

            val first = sync().syncIfDue(t0, "Mummy")
            val id = port.playlists.keys.single()

            assertTrue(first.isFailure)
            assertEquals(id, stored()[ParivaarPlaylistIdKey])
            assertNull(stored()[ParivaarTopSongsSyncedAtKey])

            hub.failRegister = null
            val second = sync().syncIfDue(t0.plusSeconds(60), "Mummy")

            assertTrue(second.isSuccess)
            assertEquals(1, port.calls.count { it == "create" })
            assertEquals(listOf(Triple("session-token", id, "Mummy")), hub.registered)
            assertEquals(t0.plusSeconds(60).toEpochMilli(), stored()[ParivaarTopSongsSyncedAtKey])
        }

    @Test
    fun `a failure is not retried on every resume of the same process`() =
        runBlocking {
            source.songs = songs(3)
            hub.failRegister = IOException("hub down")
            val sync = sync()
            sync.syncIfDue(t0, "Mummy")
            port.calls.clear()

            sync.syncIfDue(t0.plusSeconds(60), "Mummy")

            assertEquals(emptyList<String>(), port.calls)
        }

    @Test
    fun `a failed edit keeps the playlist id and picks up where it stopped`() =
        runBlocking {
            source.songs = songs(3)
            port.failAdd = IOException("quota")

            val first = sync().syncIfDue(t0, "Mummy")
            val id = port.playlists.keys.single()
            port.failAdd = null
            val second = sync().syncIfDue(t0.plusSeconds(60), "Mummy")

            assertTrue(first.isFailure)
            assertTrue(second.isSuccess)
            assertEquals(1, port.calls.count { it == "create" })
            assertEquals(listOf("song01", "song02", "song03"), port.ids(id))
        }

    @Test
    fun `nothing happens without a session, with the feature off, or with nothing played`() =
        runBlocking {
            source.songs = songs(3)
            identity.token = null
            assertTrue(sync().syncIfDue(t0, "Mummy").isSuccess)
            identity.token = "session-token"
            assertTrue(sync(enabled = false).syncIfDue(t0, "Mummy").isSuccess)
            source.songs = emptyList()
            assertTrue(sync().syncIfDue(t0, "Mummy").isSuccess)

            assertEquals(emptyList<String>(), port.calls)
            assertEquals(emptyList<Any>(), hub.registered)
        }

    @Test
    fun `a long member name is shortened for the hub`() =
        runBlocking {
            source.songs = songs(1)

            sync().syncIfDue(t0, "x".repeat(80))

            assertEquals(50, hub.registered.single().third.length)
        }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: final fix wave (I3) — car mode survives the app process dying mid-drive: the room code is kept, car mode
// comes back when the phone reconnects as host of that room, and the code is forgotten only when it stops hosting.
package com.metrolist.music.saraswati.family.sangat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.LiveSangatRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SangatCarModeRestoreTest {
    @get:Rule val folder = TemporaryFolder()

    private class MemoryStore : CarRoomStore {
        var code: String? = null
        var failing = false

        override suspend fun load(): String? = if (failing) error("storage") else code

        override suspend fun save(roomCode: String) {
            if (failing) error("storage")
            code = roomCode
        }

        override suspend fun clear() {
            if (failing) error("storage")
            code = null
        }
    }

    private class Hub : SangatHubApi {
        override suspend fun getLiveSangat(sessionToken: String): Result<List<LiveSangatRoom>> = Result.success(emptyList())

        override suspend fun setSangatCarMode(
            sessionToken: String,
            roomCode: String,
            carMode: Boolean,
        ): Result<Unit> = Result.success(Unit)
    }

    private val identity =
        object : HubIdentity {
            override suspend fun sessionToken() = "sess"

            override suspend fun deviceId() = "dev-1"
        }

    private val store = MemoryStore()

    // A new instance each time: the app process was killed and started again.
    private fun process() = SangatCarMode(Hub(), identity, store)

    @Test
    fun `marking a room keeps its code, and a new process starts with car mode off`() =
        runBlocking {
            process().markHostedRoom("ABCD1234")

            assertEquals("ABCD1234", store.code)
            assertFalse(process().active.value)
        }

    @Test
    fun `reconnecting as host of that room turns car mode back on`() =
        runBlocking {
            process().markHostedRoom("ABCD1234")
            val restarted = process()

            assertTrue(restarted.resumeOnReconnect("ABCD1234", isHost = true))

            assertTrue(restarted.active.value)
            assertEquals("the code stays until the hosting ends", "ABCD1234", store.code)
        }

    @Test
    fun `a different room, a guest seat or no saved room leave car mode off`() =
        runBlocking {
            val none = process()
            assertFalse(none.resumeOnReconnect("ABCD1234", isHost = true))
            assertFalse(none.active.value)

            process().markHostedRoom("ABCD1234")
            val restarted = process()
            assertFalse(restarted.resumeOnReconnect("ZZZZ9999", isHost = true))
            assertFalse(restarted.resumeOnReconnect("ABCD1234", isHost = false))
            assertFalse(restarted.active.value)
        }

    @Test
    fun `ending the hosting forgets the room`() =
        runBlocking {
            val first = process()
            first.markHostedRoom("ABCD1234")

            first.end()

            assertNull(store.code)
            assertFalse(process().resumeOnReconnect("ABCD1234", isHost = true))
        }

    @Test
    fun `forgetting a dead session clears the code without touching the live flag`() =
        runBlocking {
            val mode = process()
            mode.markHostedRoom("ABCD1234")

            mode.forget()

            assertNull(store.code)
            assertTrue(mode.active.value)
        }

    @Test
    fun `only the host to anything-else change ends car mode, not the first role of a new process`() {
        assertTrue(SangatCarMode.endsWith(RoomRole.HOST, RoomRole.NONE))
        assertTrue(SangatCarMode.endsWith(RoomRole.HOST, RoomRole.GUEST))
        assertFalse(SangatCarMode.endsWith(RoomRole.NONE, RoomRole.NONE))
        assertFalse(SangatCarMode.endsWith(RoomRole.NONE, RoomRole.HOST))
        assertFalse(SangatCarMode.endsWith(RoomRole.HOST, RoomRole.HOST))
        assertFalse(SangatCarMode.endsWith(RoomRole.GUEST, RoomRole.NONE))
    }

    @Test
    fun `unreadable storage never breaks car mode`() =
        runBlocking {
            store.failing = true
            val mode = process()

            assertTrue(mode.markHostedRoom("ABCD1234").isSuccess)
            assertTrue(mode.active.value)
            assertFalse(process().resumeOnReconnect("ABCD1234", isHost = true))
            mode.end()
            assertFalse(mode.active.value)
        }

    @Test
    fun `the code is kept in a real DataStore across instances`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
                val first = SangatCarMode(Hub(), identity, DataStoreCarRoomStore(dataStore))
                first.markHostedRoom("ABCD1234")

                val restarted = SangatCarMode(Hub(), identity, DataStoreCarRoomStore(dataStore))
                assertTrue(restarted.resumeOnReconnect("abcd1234", isHost = true))

                restarted.end()
                assertFalse(SangatCarMode(Hub(), identity, DataStoreCarRoomStore(dataStore)).resumeOnReconnect("ABCD1234", true))
            } finally {
                scope.cancel()
            }
        }
}

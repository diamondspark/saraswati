/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — the announced-rooms set survives a new store over the same file (a restart).
package com.metrolist.music.saraswati.family.sangat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnnouncedRoomsStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `starts empty, keeps what is written, and applies each update to the stored set`() =
        runBlocking {
            val file = folder.newFile("rooms.preferences_pb")
            val store = DataStoreAnnouncedRoomsStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(emptySet<String>(), store.read())

            store.update { it + "AAAA" }
            store.update { it + "BBBB" }
            assertEquals(setOf("AAAA", "BBBB"), store.read())

            store.update { it.filterTo(HashSet()) { code -> code == "BBBB" } }
            assertEquals(setOf("BBBB"), store.read())
        }
}

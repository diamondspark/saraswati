/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.snooze

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Exercises SnoozeActions, which holds the ViewModel's logic, so it runs without the Android main dispatcher.
class SnoozeViewModelTest {
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

    private fun actions(store: SnoozeStore) = SnoozeActions(store)

    @Test
    fun `snoozing the playing song skips to the next one`() {
        val store = SnoozeStore(dataStore, { now }, scope)
        var skipped = 0
        blocking { actions(store).snooze("vid00000001", "Veena", "Artist", playingMediaId = "vid00000001") { skipped++ } }

        assertEquals(1, skipped)
        assertEquals(setOf("vid00000001"), store.activeIds())
    }

    @Test
    fun `snoozing a song that is not playing does not skip`() {
        val store = SnoozeStore(dataStore, { now }, scope)
        var skipped = 0
        blocking { actions(store).snooze("vid00000001", "Veena", "Artist", playingMediaId = "vid00000002") { skipped++ } }

        assertEquals(0, skipped)
    }

    @Test
    fun `undo restores the song and does not skip back`() {
        val store = SnoozeStore(dataStore, { now }, scope)
        var skipped = 0
        val a = actions(store)
        blocking { a.snooze("vid00000001", "Veena", "Artist", playingMediaId = "vid00000001") { skipped++ } }
        blocking { a.undo("vid00000001") }

        assertFalse(store.activeIds().contains("vid00000001"))
        assertEquals("undo never skips", 1, skipped)
    }

    @Test
    fun `wake from the list removes it`() {
        val store = SnoozeStore(dataStore, { now }, scope)
        val a = actions(store)
        blocking { a.snooze("vid00000001", "Veena", "Artist", playingMediaId = null) { } }
        blocking { a.wake("vid00000001") }

        assertEquals(emptyList<SnoozedSong>(), blocking { store.snoozed.first() })
        assertTrue(store.activeIds().isEmpty())
    }
}

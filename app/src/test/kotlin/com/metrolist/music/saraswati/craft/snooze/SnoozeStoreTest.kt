/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.snooze

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnoozeStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private var now = 1_000_000L

    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store() = SnoozeStore(dataStore, { now }, scope)

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `a snooze expires after 30 days`() {
        val store = store()
        blocking { store.awaitHydrated() }
        blocking { store.snooze("vid00000001", "Veena", "Artist") }

        now += 30 * day - 1
        assertEquals(setOf("vid00000001"), store.activeIds())

        now += 1
        assertEquals(emptySet<String>(), store.activeIds())
    }

    @Test
    fun `the 501st snooze evicts the one ending soonest`() {
        val store = store()
        blocking { store.awaitHydrated() }
        repeat(SnoozeStore.SNOOZE_MAX) { index ->
            blocking { store.snooze("vid%08d".format(index), "t$index", "a") }
            now += 1_000
        }
        assertEquals(SnoozeStore.SNOOZE_MAX, store.activeIds().size)
        assertTrue("oldest is still there", "vid00000000" in store.activeIds())

        blocking { store.snooze("vid-new-501", "new", "a") }

        val ids = store.activeIds()
        assertEquals(SnoozeStore.SNOOZE_MAX, ids.size)
        assertTrue("the soonest-ending one is gone", "vid00000000" !in ids)
        assertTrue("the new snooze is kept", "vid-new-501" in ids)
    }

    @Test
    fun `wake removes a snooze at once`() {
        val store = store()
        blocking { store.awaitHydrated() }
        blocking { store.snooze("vid00000001", "Veena", "Artist") }
        blocking { store.wake("vid00000001") }

        assertEquals(emptySet<String>(), store.activeIds())
        assertEquals(emptyList<SnoozedSong>(), blocking { store.snoozed.first() })
    }

    @Test
    fun `snoozing the same song twice extends it instead of duplicating it`() {
        val store = store()
        blocking { store.awaitHydrated() }
        blocking { store.snooze("vid00000001", "Veena", "Artist") }
        now += 10 * day
        blocking { store.snooze("vid00000001", "Veena", "Artist") }

        val list = blocking { store.snoozed.first() }
        assertEquals(1, list.size)
        assertEquals(now + 30 * day, list.single().until)
    }

    @Test
    fun `the list is sorted by soonest wake-up`() {
        val store = store()
        blocking { store.awaitHydrated() }
        blocking { store.snooze("older", "O", "a") }
        now += day
        blocking { store.snooze("newer", "N", "a") }

        val list = blocking { store.snoozed.first() }
        assertEquals(listOf("older", "newer"), list.map { it.videoId })
    }

    @Test
    fun `a snooze made before storage is read survives the read`() {
        val store = store()
        blocking { store.snooze("vid00000001", "Veena", "Artist") }
        assertEquals(setOf("vid00000001"), store.activeIds())
        assertEquals(setOf("vid00000001"), blocking { store.awaitActiveIds() })
    }

    @Test
    fun `a snooze survives a new store reading the same storage`() {
        blocking { store().snooze("vid00000001", "Veena", "Artist") }

        val reopened = store()
        assertEquals(setOf("vid00000001"), blocking { reopened.awaitActiveIds() })
    }

    @Test
    fun `corrupt stored data reads as no snoozes`() {
        blocking {
            dataStore.edit { it[stringPreferencesKey("sarCraftSnoozes")] = "{not a list" }
        }
        val store = store()
        assertEquals(emptySet<String>(), blocking { store.awaitActiveIds() })
        assertEquals(emptyList<SnoozedSong>(), blocking { store.snoozed.first() })
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 11 — the last Parivaar Mix, kept on the phone against a real file-backed DataStore.
// The car reads cached() without waiting, so it must reflect a save at once and never throw on damaged storage.
package com.metrolist.music.saraswati.family.parivaar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.ParivaarMixKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParivaarMixStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    private val mix =
        ParivaarMix(
            builtAt = 1_700_000_000_000L,
            tracks =
                listOf(
                    MixTrack("video000001", "बॉलीवुड", "लता", "https://i.ytimg.com/a.jpg", 215_000L),
                    MixTrack("video000002", "Plain", "Artist", null, null),
                ),
        )

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store(source: DataStore<Preferences> = dataStore) = ParivaarMixStore(source, scope)

    private fun awaitHydrated(store: ParivaarMixStore) = runBlocking { withTimeout(5_000) { store.hydration.join() } }

    @Test
    fun `round-trips`() =
        runBlocking {
            store().save(mix)

            assertEquals(mix, withTimeout(5_000) { store().mix.first() })
        }

    @Test
    fun `a fresh phone reads as an empty mix`() =
        runBlocking {
            assertEquals(ParivaarMix.EMPTY, withTimeout(5_000) { store().mix.first() })
            assertTrue(ParivaarMix.EMPTY.tracks.isEmpty())
        }

    @Test
    fun `corrupt data reads as an empty mix`() {
        runBlocking { dataStore.edit { it[ParivaarMixKey] = "{not json" } }

        val store = store()
        awaitHydrated(store)

        assertEquals(ParivaarMix.EMPTY, runBlocking { withTimeout(5_000) { store.mix.first() } })
        assertEquals(ParivaarMix.EMPTY, store.cached())
    }

    @Test
    fun `cached returns the last saved mix without suspending`() {
        val store = store()

        runBlocking { store.save(mix) }

        assertEquals(mix, store.cached())
    }

    @Test
    fun `cached is empty before anything is saved`() {
        assertEquals(ParivaarMix.EMPTY, store().cached())
    }

    @Test
    fun `cached survives a restart`() {
        runBlocking { store().save(mix) }

        val restarted = store()
        awaitHydrated(restarted)

        assertEquals(mix, restarted.cached())
    }

    @Test
    fun `a mix saved while storage is still loading is not overwritten by the older stored one`() {
        runBlocking { store().save(mix) }
        val readOld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow =
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> =
                    dataStore.data.onEach {
                        readOld.complete(Unit)
                        release.await()
                    }

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = dataStore.updateData(transform)
            }
        val newer = ParivaarMix(builtAt = mix.builtAt + 1, tracks = mix.tracks.take(1))

        val store = store(slow)
        runBlocking {
            withTimeout(5_000) { readOld.await() }
            store.save(newer)
        }
        release.complete(Unit)
        awaitHydrated(store)

        assertEquals(newer, store.cached())
    }

    // SARASWATI: Phase 4 review fix (13)
    @Test
    fun `a failed write leaves cached where storage is`() {
        val failing =
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> = dataStore.data

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw java.io.IOException("disk full")
            }
        val store = store(failing)
        awaitHydrated(store)

        val failed = runCatching { runBlocking { store.save(mix) } }

        assertTrue(failed.isFailure)
        assertEquals(ParivaarMix.EMPTY, store.cached())
    }

    @Test
    fun `a stored mix longer than fifty is cut to fifty`() {
        val long = ParivaarMix(1L, (1..60).map { MixTrack("video%06d".format(it), "T", "A", null, null) })
        runBlocking { store().save(long) }

        val restarted = store()
        awaitHydrated(restarted)

        assertEquals(long.tracks.take(ParivaarMixBuilder.MIX_MAX), restarted.cached().tracks)
    }
}

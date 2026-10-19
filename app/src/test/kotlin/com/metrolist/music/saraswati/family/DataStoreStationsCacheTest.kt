/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — StationsCache against a real file-backed DataStore.
// What matters here is the contract Saral and the car's browse tree rely on:
// the snapshot is synchronous and never waits for storage or the network, a
// list survives a restart, an empty hub answer never wipes a good list, and
// damaged storage reads as "nothing cached", not a crash.
//
// Task 11: the cache also says whether it has loaded. The car can ask for its
// Stations folder before storage has been read, and an empty list then must
// not be mistaken for "the family has no stations".
package com.metrolist.music.saraswati.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.FamilyStationsCacheKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

class DataStoreStationsCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    private val bollywood = Station("s1", "बॉलीवुड क्लासिक्स", "video000001", 0)
    private val bhajan = Station("s2", "भजन और भक्ति गीत", "video000002", 2)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun cache(store: DataStore<Preferences> = dataStore) = DataStoreStationsCache(store, scope)

    private fun awaitHydrated(cache: DataStoreStationsCache) = runBlocking { withTimeout(5_000) { cache.hydration.join() } }

    @Test
    fun `an empty phone has an empty snapshot, read without suspending`() {
        val cache = cache()

        assertTrue(cache.snapshot().isEmpty())
        assertTrue(cache.stations.value.isEmpty())
    }

    @Test
    fun `a replaced list is in the snapshot at once`() =
        runBlocking {
            val cache = cache()

            cache.replace(listOf(bollywood, bhajan))

            assertEquals(listOf(bollywood, bhajan), cache.snapshot())
        }

    @Test
    fun `a list survives a restart`() =
        runBlocking {
            cache().replace(listOf(bollywood, bhajan))

            val restarted = cache()
            awaitHydrated(restarted)

            assertEquals(listOf(bollywood, bhajan), restarted.snapshot())
        }

    @Test
    fun `an empty answer from the hub never wipes a good list`() =
        runBlocking {
            val cache = cache()
            cache.replace(listOf(bollywood, bhajan))

            cache.replace(emptyList())

            assertEquals(listOf(bollywood, bhajan), cache.snapshot())
            val restarted = cache()
            awaitHydrated(restarted)
            assertEquals(listOf(bollywood, bhajan), restarted.snapshot())
        }

    @Test
    fun `a later list replaces the earlier one in order`() =
        runBlocking {
            val cache = cache()
            cache.replace(listOf(bollywood, bhajan))

            cache.replace(listOf(bhajan))

            assertEquals(listOf(bhajan), cache.snapshot())
        }

    @Test
    fun `damaged storage reads as nothing cached`() {
        runBlocking { dataStore.edit { it[FamilyStationsCacheKey] = "{not json" } }

        val cache = cache()
        awaitHydrated(cache)

        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun `a stored list longer than six is cut to six`() {
        val many = (1..9).map { Station("s$it", "Station $it", "video00000$it", 0) }
        runBlocking { cache().replace(many) }

        val restarted = cache()
        awaitHydrated(restarted)

        assertEquals(many.take(6), restarted.snapshot())
    }

    @Test
    fun `a list saved while storage is still loading is not overwritten by the older stored one`() {
        runBlocking { cache().replace(listOf(bollywood)) }
        val readOldValue = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Storage hands over the old list only after the new one has been saved.
        val slow =
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> =
                    dataStore.data.onEach {
                        readOldValue.complete(Unit)
                        release.await()
                    }

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = dataStore.updateData(transform)
            }

        val cache = cache(slow)
        runBlocking {
            withTimeout(5_000) { readOldValue.await() }
            cache.replace(listOf(bhajan))
        }
        release.complete(Unit)
        awaitHydrated(cache)

        assertEquals(listOf(bhajan), cache.snapshot())
    }

    @Test
    fun `the flow tells watchers when the list changes`() =
        runBlocking {
            val cache = cache()

            cache.replace(listOf(bollywood))

            assertEquals(listOf(bollywood), withTimeout(5_000) { cache.stations.first { it.isNotEmpty() } })
        }

    // --- Task 11: loaded or not ---------------------------------------------------------------------------

    /** Storage that hands over its data only once [release] is completed. */
    private fun gated(release: CompletableDeferred<Unit>) =
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = dataStore.data.onEach { release.await() }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = dataStore.updateData(transform)
        }

    @Test
    fun `a cache whose storage has not answered is empty and not loaded`() {
        val release = CompletableDeferred<Unit>()
        val cache = cache(gated(release))

        assertTrue(cache.snapshot().isEmpty())
        assertFalse(cache.loaded.value)

        release.complete(Unit)
        awaitHydrated(cache)
    }

    @Test
    fun `storage that answers with a list makes the cache loaded, with that list`() {
        runBlocking { cache().replace(listOf(bollywood, bhajan)) }

        val restarted = cache()
        awaitHydrated(restarted)

        assertTrue(restarted.loaded.value)
        assertEquals(listOf(bollywood, bhajan), restarted.snapshot())
    }

    @Test
    fun `storage that answers with nothing makes the cache loaded and empty, which is not the same as not loaded`() {
        val release = CompletableDeferred<Unit>()
        val cache = cache(gated(release))
        assertFalse(cache.loaded.value)

        release.complete(Unit)
        awaitHydrated(cache)

        assertTrue(cache.loaded.value)
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun `damaged storage makes the cache loaded and empty`() {
        runBlocking { dataStore.edit { it[FamilyStationsCacheKey] = "{not json" } }

        val cache = cache()
        awaitHydrated(cache)

        assertTrue(cache.loaded.value)
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun `a list saved while storage is still loading makes the cache loaded at once`() {
        val release = CompletableDeferred<Unit>()
        val cache = cache(gated(release))

        runBlocking { cache.replace(listOf(bhajan)) }

        assertTrue(cache.loaded.value)
        assertEquals(listOf(bhajan), cache.snapshot())
        release.complete(Unit)
        awaitHydrated(cache)
        assertEquals(listOf(bhajan), cache.snapshot())
    }

    @Test
    fun `an empty answer from the hub does not make a cache loaded`() {
        val release = CompletableDeferred<Unit>()
        val cache = cache(gated(release))

        runBlocking { cache.replace(emptyList()) }

        assertFalse(cache.loaded.value)
        release.complete(Unit)
        awaitHydrated(cache)
    }

    @Test
    fun `nobody ever sees loaded beside a list from before`() {
        runBlocking { cache().replace(listOf(bollywood)) }
        val release = CompletableDeferred<Unit>()
        val cache = cache(gated(release))
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<Boolean, List<Station>>>()
        val watcher =
            scope.launch(Dispatchers.Unconfined) {
                combine(cache.loaded, cache.stations) { loaded, list -> loaded to list }.toList(seen)
            }

        release.complete(Unit)
        awaitHydrated(cache)
        watcher.cancel()

        assertTrue("loaded must not be seen with the empty list from before: $seen", seen.none { it.first && it.second.isEmpty() })
        assertEquals(true to listOf(bollywood), seen.last())
    }
}

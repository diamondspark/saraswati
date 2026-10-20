package com.metrolist.music.saraswati.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.StationOfflineSetsKey
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StationOfflineStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    private fun set(
        id: String,
        seed: String,
    ) = StationOfflineSet(id, seed, listOf(MixTrack("video000001", "बॉलीवुड", "लता", null, 215_000L)), 1_700_000_000_000L)

    private fun station(
        id: String,
        seed: String,
    ) = Station(id, "Title", seed, 0)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store() = StationOfflineStore(dataStore, scope)

    private fun hydrated(store: StationOfflineStore) = store.also { runBlocking { withTimeout(5_000) { it.hydration.join() } } }

    @Test
    fun `a saved set is read back at once and after a restart`() =
        runBlocking {
            val first = hydrated(store())
            first.save(set("s1", "seed0000001"))

            assertEquals(set("s1", "seed0000001"), first.cached()["s1"])
            assertEquals(set("s1", "seed0000001"), hydrated(store()).cached()["s1"])
            assertEquals("s1", first.forSeed("seed0000001")?.stationId)
            assertNull(first.forSeed("other000000"))
        }

    @Test
    fun `retain drops removed stations and changed seeds`() =
        runBlocking {
            val store = hydrated(store())
            store.save(set("keep", "seed0000001"))
            store.save(set("gone", "seed0000002"))
            store.save(set("moved", "seed0000003"))

            store.retain(listOf(station("keep", "seed0000001"), station("moved", "seed0000009")))

            assertEquals(setOf("keep"), store.cached().keys)
            assertEquals(setOf("keep"), hydrated(store()).cached().keys)
        }

    @Test
    fun `corrupt data reads as empty`() =
        runBlocking {
            dataStore.edit { it[StationOfflineSetsKey] = "{not json" }

            val store = hydrated(store())

            assertEquals(emptyMap<String, StationOfflineSet>(), store.cached())
            store.save(set("s1", "seed0000001"))
            assertEquals(setOf("s1"), store.cached().keys)
        }
}

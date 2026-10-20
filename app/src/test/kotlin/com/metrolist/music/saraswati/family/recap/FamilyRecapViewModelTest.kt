/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 15 — the recap card's view model over a real file-backed DataStore.
package com.metrolist.music.saraswati.family.recap

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.parivaar.MemberTopSongs
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FamilyRecapViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: RecapSnapshotStore
    private val features = MutableStateFlow<Map<FamilyFeature, Boolean>>(emptyMap())
    private val today = LocalDate.of(2026, 10, 19)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        store = RecapSnapshotStore(dataStore, Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun viewModel() = FamilyRecapViewModel(store, PairedIdentity("me"), features, { today }, scope)

    private fun track(id: String) = MixTrack(id, "Title $id", "Artist", null, null)

    private fun recordTwoLists() =
        runBlocking {
            store.record(
                listOf(
                    MemberTopSongs("me", "Mohit", listOf(track("shared"), track("a"))),
                    MemberTopSongs("mum", "Mummy", listOf(track("shared"), track("m"))),
                ),
            )
        }

    @Test
    fun `the card is hidden with no recap`() =
        runBlocking {
            val vm = viewModel()
            store.ensureLoaded()

            assertNull(withTimeout(5_000) { vm.recap.first { it == null } })
            assertNull(vm.recap.value)
        }

    @Test
    fun `the card is hidden while parivaar_mix is off`() =
        runBlocking {
            recordTwoLists()
            features.value = mapOf(FamilyFeature.PARIVAAR_MIX to true)
            val vm = viewModel()
            assertNotNull(withTimeout(5_000) { vm.recap.first { it != null } })

            features.value = mapOf(FamilyFeature.PARIVAAR_MIX to false)

            assertNull(withTimeout(5_000) { vm.recap.first { it == null } })
        }

    @Test
    fun `playing the anthem starts its radio`() =
        runBlocking {
            recordTwoLists()
            val vm = viewModel()
            withTimeout(5_000) { vm.recap.first { it != null } }

            var started: YouTubeQueue? = null
            vm.playAnthem { started = it }

            assertNotNull(started)
            assertEquals("shared", vm.anthemRadio()!!.videoId)
        }

    private class PairedIdentity(
        private val device: String,
    ) : HubIdentity {
        override suspend fun sessionToken(): String? = "token"

        override suspend fun deviceId(): String? = device
    }
}

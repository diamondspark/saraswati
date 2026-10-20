/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 20 — the Parivaar Mix view model against a real file-backed DataStore, so the toggle
// and the held mix are read back the way the app reads them.
package com.metrolist.music.saraswati.family.parivaar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.music.constants.CountSharedPlaysKey
import com.metrolist.music.saraswati.family.FamilyFeature
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParivaarViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: ParivaarMixStore
    private val features = MutableStateFlow<Map<FamilyFeature, Boolean>>(emptyMap())

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        store = ParivaarMixStore(dataStore, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun viewModel() = ParivaarViewModel(store, dataStore, features, scope)

    private fun ready(vararg ids: String) = ParivaarMix(1_000L, ids.map { track(it) })

    @Test
    fun `play starts a ParivaarMixQueue with the cached tracks`() =
        runBlocking {
            store.awaitHydrated()
            val mix = ready("a", "b", "c")
            store.save(mix)
            val vm = viewModel()

            var started: ParivaarMixQueue? = null
            vm.play { started = it }

            assertEquals(mix.tracks, started?.tracks)
        }

    @Test
    fun `an empty mix shows the needs-two-members state`() =
        runBlocking {
            store.awaitHydrated()
            store.save(ParivaarMix(1_000L, emptyList()))
            val vm = viewModel()

            val state = withTimeout(5_000) { vm.state.first { it == ParivaarUiState.NeedsTwoMembers } }

            assertEquals(ParivaarUiState.NeedsTwoMembers, state)
            var started = false
            vm.play { started = true }
            assertTrue("an empty mix starts nothing", !started)
        }

    @Test
    fun `the toggle writes CountSharedPlaysKey`() =
        runBlocking {
            val vm = viewModel()
            assertEquals(false, withTimeout(5_000) { vm.countSharedPlays.first { !it } })

            vm.setCountSharedPlays(true)

            assertEquals(true, withTimeout(5_000) { vm.countSharedPlays.first { it } })
            assertEquals(true, dataStore.data.first()[CountSharedPlaysKey])
            vm.setCountSharedPlays(false)
            assertEquals(false, withTimeout(5_000) { vm.countSharedPlays.first { !it } })
        }

    @Test
    fun `each section is hidden while its switch is off`() =
        runBlocking {
            store.awaitHydrated()
            store.save(ready("a", "b"))
            features.value = mapOf(FamilyFeature.PARIVAAR_MIX to false, FamilyFeature.SANGAT to true)
            val vm = viewModel()

            assertEquals(ParivaarUiState.Hidden, withTimeout(5_000) { vm.state.first { it == ParivaarUiState.Hidden } })
            assertEquals(false, vm.visible.value)
            assertEquals(true, vm.tasteVisible.value)
            var started = false
            vm.play { started = true }
            assertTrue("a hidden mix starts nothing", !started)

            features.value = mapOf(FamilyFeature.PARIVAAR_MIX to false, FamilyFeature.SANGAT to false)
            assertEquals(false, withTimeout(5_000) { vm.tasteVisible.first { !it } })
        }
}

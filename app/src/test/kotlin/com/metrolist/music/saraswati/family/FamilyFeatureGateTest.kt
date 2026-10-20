/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 8 — FamilyFeatureGate against a fake hub and a real file-backed DataStore: fail
// open before any answer, one switch off at a time, last values kept through a failure and a restart.
package com.metrolist.music.saraswati.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

class FamilyFeatureGateTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var hub: FakeHub
    private var token: String? = "sess"

    private val identity =
        object : HubIdentity {
            override suspend fun sessionToken() = token

            override suspend fun deviceId() = if (token == null) null else "dev-1"
        }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        hub = FakeHub()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun gate(store: DataStore<Preferences> = dataStore): FamilyFeatureGate =
        FamilyFeatureGate(hub, identity, store, scope).also { runBlocking { withTimeout(5_000) { it.hydration.join() } } }

    @Test
    fun `every feature is enabled before any answer`() {
        val gate = gate()

        FamilyFeature.entries.forEach { assertTrue(it.key, gate.isEnabled(it)) }
        assertEquals(FamilyFeature.entries.toSet(), gate.enabled.value.keys)
        assertTrue(gate.enabled.value.values.all { it })
    }

    @Test
    fun `a fetched false disables only that feature`() =
        runBlocking {
            val gate = gate()
            hub.killSwitches = mapOf("sangat" to false, "parivaar_mix" to true, "family_playlists" to true, "cast_relay" to true)

            gate.refresh()

            assertFalse(gate.isEnabled(FamilyFeature.SANGAT))
            assertTrue(gate.isEnabled(FamilyFeature.PARIVAAR_MIX))
            assertTrue(gate.isEnabled(FamilyFeature.FAMILY_PLAYLISTS))
            assertTrue(gate.isEnabled(FamilyFeature.CAST_RELAY))
            assertEquals(false, gate.enabled.value[FamilyFeature.SANGAT])
            assertEquals(listOf("getKillSwitches(sess)"), hub.calls.toList())
        }

    @Test
    fun `ask_saraswati is enabled before any answer`() {
        val gate = gate()

        assertTrue(gate.isEnabled(FamilyFeature.ASK_SARASWATI))
        assertEquals("ask_saraswati", FamilyFeature.ASK_SARASWATI.key)
    }

    @Test
    fun `a fetched false disables only ask_saraswati, and a key this build does not know is ignored`() =
        runBlocking {
            val gate = gate()
            hub.killSwitches =
                mapOf(
                    "sangat" to true,
                    "parivaar_mix" to true,
                    "family_playlists" to true,
                    "cast_relay" to true,
                    "ask_saraswati" to false,
                    "a_future_switch" to false,
                )

            gate.refresh()

            assertFalse(gate.isEnabled(FamilyFeature.ASK_SARASWATI))
            assertTrue(gate.enabled.value.filterKeys { it != FamilyFeature.ASK_SARASWATI }.values.all { it })
            assertEquals(FamilyFeature.entries.toSet(), gate.enabled.value.keys)
        }

    @Test
    fun `a later true turns the feature back on`() =
        runBlocking {
            val gate = gate()
            hub.killSwitches = mapOf("cast_relay" to false)
            gate.refresh()
            hub.killSwitches = mapOf("cast_relay" to true)

            gate.refresh()

            assertTrue(gate.isEnabled(FamilyFeature.CAST_RELAY))
        }

    @Test
    fun `a failed refresh keeps the last fetched values`() =
        runBlocking {
            val gate = gate()
            hub.killSwitches = mapOf("sangat" to false)
            gate.refresh()

            for (failure in listOf(OFFLINE, hubFailure(401), hubFailure(429), hubFailure(503), RuntimeException("boom"))) {
                hub.failWith("getKillSwitches", failure)
                gate.refresh()
                assertFalse(failure.toString(), gate.isEnabled(FamilyFeature.SANGAT))
                assertTrue(gate.isEnabled(FamilyFeature.CAST_RELAY))
            }
        }

    @Test
    fun `a failed first refresh leaves every feature on`() =
        runBlocking {
            val gate = gate()
            hub.failWith("getKillSwitches", OFFLINE)

            gate.refresh()

            assertTrue(gate.enabled.value.values.all { it })
        }

    @Test
    fun `without a session token the hub is not asked`() =
        runBlocking {
            token = null
            val gate = gate()

            gate.refresh()

            assertTrue(hub.calls.isEmpty())
            assertTrue(gate.enabled.value.values.all { it })
        }

    @Test
    fun `an unknown key from the hub is ignored`() =
        runBlocking {
            val gate = gate()
            hub.killSwitches = mapOf("sangat" to true, "time_travel" to false, "cast_relay" to false)

            gate.refresh()

            assertEquals(FamilyFeature.entries.toSet(), gate.enabled.value.keys)
            assertTrue(gate.isEnabled(FamilyFeature.SANGAT))
            assertFalse(gate.isEnabled(FamilyFeature.CAST_RELAY))
            assertTrue(gate.isEnabled(FamilyFeature.PARIVAAR_MIX))
        }

    @Test
    fun `a feature the hub leaves out stays on`() =
        runBlocking {
            val gate = gate()
            hub.killSwitches = emptyMap()

            gate.refresh()

            assertTrue(gate.enabled.value.values.all { it })
        }

    @Test
    fun `values survive a process restart`() =
        runBlocking {
            hub.killSwitches = mapOf("parivaar_mix" to false, "family_playlists" to false)
            gate().refresh()

            hub.failWith("getKillSwitches", OFFLINE)
            val restarted = gate()

            assertFalse(restarted.isEnabled(FamilyFeature.PARIVAAR_MIX))
            assertFalse(restarted.isEnabled(FamilyFeature.FAMILY_PLAYLISTS))
            assertTrue(restarted.isEnabled(FamilyFeature.SANGAT))
            assertTrue(restarted.isEnabled(FamilyFeature.CAST_RELAY))
        }

    @Test
    fun `a restart after everything was turned back on starts with everything on`() =
        runBlocking {
            hub.killSwitches = mapOf("sangat" to false)
            gate().refresh()
            hub.killSwitches = mapOf("sangat" to true)
            gate().refresh()

            assertTrue(gate().enabled.value.values.all { it })
        }

    @Test
    fun `work that leaves the phone waits for the hub's first answer`() =
        runBlocking {
            val gate = gate()

            assertFalse(gate.hasHubAnswer)
            assertFalse(gate.allows(FamilyFeature.PARIVAAR_MIX))

            hub.failWith("getKillSwitches", OFFLINE)
            gate.refresh()
            assertFalse(gate.hasHubAnswer)
            assertFalse(gate.allows(FamilyFeature.PARIVAAR_MIX))

            hub.heal("getKillSwitches")
            gate.refresh()
            assertTrue(gate.hasHubAnswer)
            assertTrue(gate.allows(FamilyFeature.PARIVAAR_MIX))
        }

    @Test
    fun `an answer that switched a feature off blocks it, and the answer is remembered across a restart`() =
        runBlocking {
            hub.killSwitches = mapOf("parivaar_mix" to false)
            gate().refresh()

            val restarted = gate()

            assertTrue(restarted.hasHubAnswer)
            assertFalse(restarted.allows(FamilyFeature.PARIVAAR_MIX))
            assertTrue(restarted.allows(FamilyFeature.SANGAT))
        }

    @Test
    fun `an answer with everything on is remembered too`() =
        runBlocking {
            gate().refresh()

            val restarted = gate()

            assertTrue(restarted.hasHubAnswer)
            assertTrue(restarted.allows(FamilyFeature.PARIVAAR_MIX))
        }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 11 — AskViewModel: Busy then the intent, the daily limit, an unreachable hub searching the
// text, an answer as plain text with a search action, voice sent like typed text, blank input refused, and the entry
// point gone while the switch is off.
package com.metrolist.music.saraswati.ask

import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.OFFLINE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AskViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeAskHub
    private lateinit var familyHub: FakeHub
    private lateinit var gate: FamilyFeatureGate
    private lateinit var actions: FakeAskActions
    private lateinit var stations: FakeStationsCache
    private lateinit var mixStore: com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        hub = FakeAskHub()
        familyHub = FakeHub()
        gate = askGate(scope, askDataStore(scope, folder.newFile("gate.preferences_pb")), familyHub)
        actions = FakeAskActions()
        stations = FakeStationsCache(listOf(BHAJAN_STATION))
        mixStore = askMixStore(scope, askDataStore(scope, folder.newFile("mix.preferences_pb")))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun vm(): AskViewModel {
        val repo = AskRepository(hub, FixedIdentity(), gate, Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC))
        return AskViewModel(repo, stations, mixStore, gate, scope).also { it.attach(actions) }
    }

    private fun waitUntilNotBusy(model: AskViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (model.state.value == AskUiState.Busy && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `sending shows Busy then executes the intent`() {
        hub.answer = Result.success(AskReply(AskIntent.Sangat, fallback = false))
        hub.delayMs = 300
        val model = vm()

        model.send("sangat kholo")
        assertEquals(AskUiState.Busy, model.state.value)
        waitUntilNotBusy(model)

        assertEquals(AskUiState.Idle, model.state.value)
        assertEquals(listOf("sangat"), actions.calls.toList())
    }

    @Test
    fun `the daily limit shows Kal phir poochhiye`() {
        val resetAt = Instant.parse("2026-10-20T00:00:00Z")
        hub.answer = Result.failure(AskLimitReached(resetAt))
        val model = vm()

        model.send("kuch bhi")

        assertEquals(AskUiState.Limit(resetAt), model.state.value)
        assertEquals(emptyList<String>(), actions.calls.toList())
    }

    @Test
    fun `an unavailable hub opens search for the text`() {
        hub.answer = Result.failure(OFFLINE)
        val model = vm()

        model.send("bhajan sunao")

        assertEquals(AskUiState.Idle, model.state.value)
        assertEquals(listOf("search(bhajan sunao)"), actions.calls.toList())
    }

    @Test
    fun `an answer is plain text with a search action`() {
        hub.answer = Result.success(AskReply(AskIntent.Answer("Kal baarish hogi"), fallback = false))
        val model = vm()

        model.send("kal mausam")

        assertEquals(AskUiState.Answer("Kal baarish hogi"), model.state.value)
        assertEquals(emptyList<String>(), actions.calls.toList())

        model.searchThis()

        assertEquals(listOf("search(kal mausam)"), actions.calls.toList())
    }

    @Test
    fun `a voice result is sent like typed text`() {
        hub.answer = Result.success(AskReply(AskIntent.Sangat, fallback = false))
        val model = vm()

        model.onVoiceResult("  sangat kholo  ")

        assertEquals(listOf("sangat kholo"), hub.calls.map { it.question })
        assertEquals(listOf("sangat"), actions.calls.toList())
    }

    @Test
    fun `empty or blank input cannot be sent`() {
        val model = vm()

        model.send("")
        model.send("   ")

        assertEquals(emptyList<FakeAskHub.Call>(), hub.calls.toList())
        assertEquals(AskUiState.Idle, model.state.value)
    }

    @Test
    fun `the entry point is hidden while ask_saraswati is off, and nothing is sent`() {
        val model = vm()
        runBlocking {
            familyHub.killSwitches = mapOf("ask_saraswati" to false)
            gate.refresh()
        }

        assertFalse(model.enabled.value)
        model.send("kuch bhi")
        assertEquals(emptyList<FakeAskHub.Call>(), hub.calls.toList())
    }
}

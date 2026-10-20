/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — SaralAsk, the voice path's resolver: a station or a Parivaar Mix when the hub says
// so, and the same voice search as before in every other case, including a 3-second wait that falls back.
package com.metrolist.music.saraswati.ask

import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SaralAskTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeAskHub
    private lateinit var stations: FakeStationsCache
    private lateinit var mixStore: ParivaarMixStore
    private lateinit var familyHub: FakeHub
    private lateinit var gate: FamilyFeatureGate

    private val spoken = "bhajan lagao"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        hub = FakeAskHub()
        stations = FakeStationsCache(listOf(BHAJAN_STATION))
        mixStore = askMixStore(scope, askDataStore(scope, folder.newFile("mix.preferences_pb")))
        familyHub = FakeHub()
        gate = askGate(scope, askDataStore(scope, folder.newFile("gate.preferences_pb")), familyHub)
        runBlocking { gate.refresh() }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun resolver(): SaralAsk {
        val repo = AskRepository(hub, FixedIdentity(), gate, Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC))
        return SaralAsk(repo, stations, mixStore, gate)
    }

    private fun answer(intent: AskIntent) {
        hub.answer = Result.success(AskReply(intent, fallback = false))
    }

    @Test
    fun `a station intent plays the station`() =
        runBlocking {
            answer(AskIntent.PlayStation(BHAJAN_STATION.id))

            assertEquals(SaralAction.PlayStation(BHAJAN_STATION.id), resolver().resolve(spoken))
        }

    @Test
    fun `a station the family does not have voice-searches the spoken words`() =
        runBlocking {
            answer(AskIntent.PlayStation("no-such-station"))

            assertEquals(SaralAction.VoiceSearch(spoken), resolver().resolve(spoken))
        }

    @Test
    fun `a search intent voice-searches the model's query`() =
        runBlocking {
            answer(AskIntent.Search("Bhajan Sandhya"))

            assertEquals(SaralAction.VoiceSearch("Bhajan Sandhya"), resolver().resolve(spoken))
        }

    @Test
    fun `an answer or sangat intent voice-searches the spoken text`() =
        runBlocking {
            answer(AskIntent.Answer("Aaj chhutti hai"))
            assertEquals(SaralAction.VoiceSearch(spoken), resolver().resolve(spoken))

            answer(AskIntent.Sangat)
            assertEquals(SaralAction.VoiceSearch(spoken), resolver().resolve(spoken))
        }

    @Test
    fun `a parivaar_mix intent plays the mix when it has songs and the switch is on`() =
        runBlocking {
            mixStore.save(savedMix("a1"))
            answer(AskIntent.ParivaarMix)

            assertEquals(SaralAction.ParivaarMix, resolver().resolve(spoken))
        }

    @Test
    fun `an empty parivaar_mix voice-searches the spoken words`() =
        runBlocking {
            answer(AskIntent.ParivaarMix)

            assertEquals(SaralAction.VoiceSearch(spoken), resolver().resolve(spoken))
        }

    @Test
    fun `the daily limit falls back silently`() =
        runBlocking {
            hub.answer = Result.failure(AskLimitReached(Instant.parse("2026-10-20T00:00:00Z")))

            assertEquals(SaralAction.VoiceSearch(spoken), resolver().resolve(spoken))
        }

    @Test
    fun `a 3-second timeout falls back`() =
        runBlocking {
            hub.delayMs = 5_000
            answer(AskIntent.PlayStation(BHAJAN_STATION.id))

            val started = System.nanoTime()
            val action = resolver().resolve(spoken)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(SaralAction.VoiceSearch(spoken), action)
            assertEquals(true, elapsedMs in 2_900..4_500)
        }
}

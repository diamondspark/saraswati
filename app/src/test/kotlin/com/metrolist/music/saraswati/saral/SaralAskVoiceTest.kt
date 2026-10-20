/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — the voice result in Saral, with Ask Saraswati in front of the search: a station or
// a mix plays, and every other answer searches the words the way Phase 3 always has.
package com.metrolist.music.saraswati.saral

import com.metrolist.music.saraswati.ask.AskIntent
import com.metrolist.music.saraswati.ask.AskRepository
import com.metrolist.music.saraswati.ask.AskReply
import com.metrolist.music.saraswati.ask.FakeAskHub
import com.metrolist.music.saraswati.ask.FixedIdentity
import com.metrolist.music.saraswati.ask.SaralAsk
import com.metrolist.music.saraswati.ask.askDataStore
import com.metrolist.music.saraswati.ask.askGate
import com.metrolist.music.saraswati.ask.askMixStore
import com.metrolist.music.saraswati.ask.savedMix
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FakeStore
import com.metrolist.music.saraswati.family.FamilySession
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsRefresher
import com.metrolist.music.saraswati.family.noThrottle
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

class SaralAskVoiceTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeHub
    private lateinit var family: FakeStore
    private lateinit var cache: FakeStationsCache
    private lateinit var search: FakeSearch
    private lateinit var playback: FakePlayback
    private lateinit var askHub: FakeAskHub
    private lateinit var gate: FamilyFeatureGate
    private lateinit var mixStore: ParivaarMixStore

    private val bhajan = Station("s-bhajan", "Bhajans", "seedBhajan01", 0)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        hub = FakeHub()
        family = FakeStore().apply { session = FamilySession("device-1", "the-session-token") }
        cache = FakeStationsCache(listOf(bhajan))
        search = FakeSearch()
        playback = FakePlayback()
        askHub = FakeAskHub()
        gate = askGate(scope, askDataStore(scope, folder.newFile("gate.preferences_pb")), hub, FixedIdentity())
        runBlocking { gate.refresh() }
        mixStore = askMixStore(scope, askDataStore(scope, folder.newFile("mix.preferences_pb")))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newVm(): SaralViewModel {
        val repo = AskRepository(askHub, FixedIdentity(), gate, Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC))
        val ask = SaralAsk(repo, cache, mixStore, gate)
        return SaralViewModel(
            cache,
            StationsRefresher(hub, family, cache, noThrottle()),
            search,
            FakeSignIn(),
            scope,
            ask = ask,
        ).also { it.bindPlayback(playback) }
    }

    private fun SaralViewModel.heard(text: String) {
        onMicTapped()
        onVoiceOutcome(VoiceOutcome.Heard(listOf(text)))
    }

    private fun answer(intent: AskIntent) {
        askHub.answer = Result.success(AskReply(intent, fallback = false))
    }

    @Test
    fun `a station the hub names plays its radio and is not searched`() {
        answer(AskIntent.PlayStation(bhajan.id))

        newVm().heard("bhajan lagao")

        assertEquals(listOf("radio(seedBhajan01)"), playback.calls.toList())
        assertEquals(emptyList<String>(), search.queries.toList())
    }

    @Test
    fun `a parivaar_mix intent plays the mix`() {
        runBlocking { mixStore.save(savedMix("a1", "b2")) }
        answer(AskIntent.ParivaarMix)

        newVm().heard("mera mix chalao")

        assertEquals(listOf("mix(2)"), playback.calls.toList())
    }

    @Test
    fun `a search intent searches the model's query`() {
        answer(AskIntent.Search("Bhajan Sandhya"))

        newVm().heard("bhajan lagao")

        assertEquals(listOf("Bhajan Sandhya"), search.queries.toList())
    }

    @Test
    fun `an answer intent searches the spoken words, as Saral always has`() {
        answer(AskIntent.Answer("Aaj chhutti hai"))

        newVm().heard("bhajan lagao")

        assertEquals(listOf("bhajan lagao"), search.queries.toList())
        assertEquals(emptyList<String>(), playback.calls.toList())
    }

    @Test
    fun `without the ask the recogniser's guesses are searched exactly as before`() {
        val vm = SaralViewModel(cache, StationsRefresher(hub, family, cache, noThrottle()), search, FakeSignIn(), scope).also { it.bindPlayback(playback) }

        vm.onMicTapped()
        vm.onVoiceOutcome(VoiceOutcome.Heard(listOf("pehla", "doosra")))

        assertEquals(listOf("pehla", "doosra"), search.queries.toList())
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: security-ask-explanation — the one-time explanation before Saral's voice search is first routed
// through Ask Saraswati (SaralAsk): it must appear before the first route, nothing must be asked of the hub
// until it is acknowledged, and it must not appear again afterwards.
package com.metrolist.music.saraswati.saral

import com.metrolist.music.saraswati.ask.AskIntent
import com.metrolist.music.saraswati.ask.AskRepository
import com.metrolist.music.saraswati.ask.AskReply
import com.metrolist.music.saraswati.ask.AskVoiceExplanationGate
import com.metrolist.music.saraswati.ask.FakeAskHub
import com.metrolist.music.saraswati.ask.FixedIdentity
import com.metrolist.music.saraswati.ask.SaralAsk
import com.metrolist.music.saraswati.ask.askDataStore
import com.metrolist.music.saraswati.ask.askGate
import com.metrolist.music.saraswati.ask.askMixStore
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FakeStore
import com.metrolist.music.saraswati.family.FamilyFeatureGate
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A gate whose "seen" bit lives in memory, so a test can see exactly when it is read and set. */
class FakeAskVoiceExplanationGate(
    seen: Boolean = false,
    private val failMarkSeen: Boolean = false,
) : AskVoiceExplanationGate {
    @Volatile var seen: Boolean = seen
        private set
    var markSeenCalls = 0
        private set
    var hasSeenCalls = 0
        private set

    override suspend fun hasSeen(): Boolean {
        hasSeenCalls++
        return seen
    }

    override suspend fun markSeen() {
        markSeenCalls++
        if (failMarkSeen) throw RuntimeException("DataStore write failed")
        seen = true
    }
}

class SaralAskVoiceExplanationTest {
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

    private fun newVm(explanationGate: AskVoiceExplanationGate?): SaralViewModel {
        val repo = AskRepository(askHub, FixedIdentity(), gate, Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC))
        val ask = SaralAsk(repo, cache, mixStore, gate)
        return SaralViewModel(
            cache,
            StationsRefresher(hub, family, cache, noThrottle()),
            search,
            FakeSignIn(),
            scope,
            ask = ask,
            explanationGate = explanationGate,
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
    fun `first use shows the explanation and does not route to the hub until it is acknowledged`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = false)
        val vm = newVm(explanationGate)

        vm.heard("bhajan lagao")

        assertTrue(vm.askExplanation.value)
        assertEquals(emptyList<String>(), playback.calls.toList())
        assertEquals(emptyList<String>(), search.queries.toList())
        assertFalse(explanationGate.seen)

        vm.onAskExplanationAcknowledged()

        assertFalse(vm.askExplanation.value)
        assertEquals(listOf("radio(seedBhajan01)"), playback.calls.toList())
        assertTrue(explanationGate.seen)
        assertEquals(1, explanationGate.markSeenCalls)
    }

    @Test
    fun `subsequent use skips the explanation and routes straight to the hub`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = true)
        val vm = newVm(explanationGate)

        vm.heard("bhajan lagao")

        assertFalse(vm.askExplanation.value)
        assertEquals(listOf("radio(seedBhajan01)"), playback.calls.toList())
        assertEquals(0, explanationGate.markSeenCalls)
    }

    @Test
    fun `without a gate the explanation never shows, exactly as before`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val vm = newVm(explanationGate = null)

        vm.heard("bhajan lagao")

        assertFalse(vm.askExplanation.value)
        assertEquals(listOf("radio(seedBhajan01)"), playback.calls.toList())
    }

    @Test
    fun `dismissing the overlay while the explanation is showing drops the pending ask`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = false)
        val vm = newVm(explanationGate)

        vm.heard("bhajan lagao")
        assertTrue(vm.askExplanation.value)

        vm.onOverlayDismissed()

        assertFalse(vm.askExplanation.value)
        assertEquals(emptyList<String>(), playback.calls.toList())
        assertFalse(explanationGate.seen)

        // Acknowledging now (e.g. a stray late callback) must do nothing: the pending ask was dropped.
        vm.onAskExplanationAcknowledged()
        assertEquals(emptyList<String>(), playback.calls.toList())
    }

    // SARASWATI: security-ask-explanation (review fix 2) — a failed write to the DataStore must not strand the
    // person's voice search: the ask they already asked for still runs.
    @Test
    fun `a markSeen failure does not block the ask that was waiting on it`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = false, failMarkSeen = true)
        val vm = newVm(explanationGate)

        vm.heard("bhajan lagao")
        vm.onAskExplanationAcknowledged()

        assertFalse(vm.askExplanation.value)
        assertEquals(listOf("radio(seedBhajan01)"), playback.calls.toList())
        assertEquals(VoicePhase.IDLE, vm.voice.value)
        assertEquals(1, explanationGate.markSeenCalls)
        // The write failed, but acknowledging it still means this session treats it as seen.
        assertFalse(explanationGate.seen)
    }

    // SARASWATI: security-ask-explanation (review fix 3) — once the gate has said "seen" once, it must not be
    // asked again for the life of this ViewModel.
    @Test
    fun `once seen, the gate is never read again for the life of the view model`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = true)
        val vm = newVm(explanationGate)

        vm.heard("bhajan lagao")
        assertEquals(1, explanationGate.hasSeenCalls)

        vm.heard("bhajan lagao")
        vm.heard("bhajan lagao")

        assertEquals("a gate already known to be seen is not read again", 1, explanationGate.hasSeenCalls)
    }
}

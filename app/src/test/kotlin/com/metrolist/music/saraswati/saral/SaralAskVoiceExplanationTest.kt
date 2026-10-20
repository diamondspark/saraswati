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
import com.metrolist.music.saraswati.family.FamilyFeature
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
    private val failHasSeen: Boolean = false,
) : AskVoiceExplanationGate {
    @Volatile var seen: Boolean = seen
        private set
    var markSeenCalls = 0
        private set
    var hasSeenCalls = 0
        private set

    override suspend fun hasSeen(): Boolean {
        hasSeenCalls++
        if (failHasSeen) throw RuntimeException("DataStore read failed")
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

    private fun newVm(
        explanationGate: AskVoiceExplanationGate?,
        repoSessionToken: String? = "sess",
    ): SaralViewModel {
        val repo =
            AskRepository(askHub, FixedIdentity(repoSessionToken), gate, Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC))
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

    // SARASWATI: security-ask-explanation (third-round audit fix 5, owner's product ruling) — declining used
    // to drop the pending ask with nothing happening. The owner ruled that should behave like voice search did
    // before Ask Saraswati existed: search the words normally, skip the hub.
    @Test
    fun `declining the explanation falls back to a plain search, not dropping the request`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = false)
        val vm = newVm(explanationGate)

        vm.heard("bhajan lagao")
        assertTrue(vm.askExplanation.value)

        vm.onOverlayDismissed()

        assertFalse(vm.askExplanation.value)
        assertEquals("the hub's PlayStation reply must not be acted on", emptyList<String>(), playback.calls.toList())
        assertEquals(listOf("bhajan lagao"), search.queries.toList())
        assertTrue("declining must never call the hub itself", askHub.calls.isEmpty())
        assertFalse(explanationGate.seen)

        // Acknowledging now (e.g. a stray late callback) must do nothing: the pending ask was already resolved.
        vm.onAskExplanationAcknowledged()
        assertEquals(listOf("bhajan lagao"), search.queries.toList())
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

    // SARASWATI: security-ask-explanation (third-round audit fix 1) — the explanation's claim ("sends the
    // words to a computer helper") must be true when shown: Ask Saraswati off, or no session, means
    // AskRepository.ask would never reach the hub either, so there is nothing to explain or decline out of.
    @Test
    fun `the explanation does not show, and the ask falls back to a search, when Ask Saraswati is switched off`() {
        answer(AskIntent.PlayStation(bhajan.id))
        hub.killSwitches = hub.killSwitches + (FamilyFeature.ASK_SARASWATI.key to false)
        runBlocking { gate.refresh() }
        val explanationGate = FakeAskVoiceExplanationGate(seen = false)
        val vm = newVm(explanationGate)

        vm.heard("bhajan lagao")

        assertFalse("nothing to explain when the hub will not be reached", vm.askExplanation.value)
        assertEquals(0, explanationGate.hasSeenCalls)
        assertEquals(listOf("bhajan lagao"), search.queries.toList())
        assertTrue("the hub itself must never be asked", askHub.calls.isEmpty())
    }

    @Test
    fun `the explanation does not show, and the ask falls back to a search, when there is no session`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = false)
        val vm = newVm(explanationGate, repoSessionToken = null)

        vm.heard("bhajan lagao")

        assertFalse(vm.askExplanation.value)
        assertEquals(0, explanationGate.hasSeenCalls)
        assertEquals(listOf("bhajan lagao"), search.queries.toList())
        assertTrue(askHub.calls.isEmpty())
    }

    // SARASWATI: security-ask-explanation (third-round audit fix 6) — a failed read must default to the safer
    // side (show the explanation) rather than crash or silently skip a disclosure it owes, the same way a
    // failed markSeen() write already does not block the ask.
    @Test
    fun `a hasSeen read failure falls back to showing the explanation, not crashing`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = false, failHasSeen = true)
        val vm = newVm(explanationGate)

        vm.heard("bhajan lagao")

        assertTrue("a failed read must fall back to showing the explanation", vm.askExplanation.value)
        assertEquals(emptyList<String>(), playback.calls.toList())
    }
}

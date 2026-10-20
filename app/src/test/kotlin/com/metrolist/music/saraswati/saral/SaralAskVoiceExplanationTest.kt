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
import kotlinx.coroutines.CompletableDeferred
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
        nowMs: () -> Long = { System.currentTimeMillis() },
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
            nowMs = nowMs,
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

    // SARASWATI: security-ask-explanation (fourth-round audit fix 2, critical, confirmed by running it) — the
    // instant the explanation closes, voice can still be SEARCHING (Not now's fallback search, or Okay's hub
    // call, is in flight), and SaralScreen then draws the full-screen, tap-to-cancel ListeningOverlay under the
    // same finger that just tapped the button: a very plausible second tap reaches onOverlayDismissed() and
    // used to cancel it outright — for Okay, after the words had already left the phone, with nothing to show
    // for it. These hold the fallback search in flight (FakeSearch.gate) to prove a stray tap inside the
    // debounce window does not cancel it.
    @Test
    fun `a stray second tap right after declining does not cancel the fallback search`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = false)
        var clockMs = 1_000L
        val vm = newVm(explanationGate, nowMs = { clockMs })
        search.gate = CompletableDeferred()

        vm.heard("bhajan lagao")
        assertTrue(vm.askExplanation.value)

        vm.onOverlayDismissed()
        assertEquals("the fallback search must start", VoicePhase.SEARCHING, vm.voice.value)

        // The stray second tap, well inside the debounce window.
        clockMs += 50
        vm.onOverlayDismissed()

        assertEquals("the debounced dismiss must not cancel the fallback search", VoicePhase.SEARCHING, vm.voice.value)

        search.gate?.complete(Unit)
        // A cancelled coroutine's remaining code (including this write) never runs: VoicePhase.IDLE here, not
        // SEARCHING or stuck, is what actually proves the stray tap did not cancel it.
        assertEquals("the search must be allowed to finish, proving it was never cancelled", VoicePhase.IDLE, vm.voice.value)
        assertEquals(listOf("bhajan lagao"), search.queries.toList())
    }

    @Test
    fun `a dismiss outside the debounce window still cancels a stuck search`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = false)
        var clockMs = 1_000L
        val vm = newVm(explanationGate, nowMs = { clockMs })
        search.gate = CompletableDeferred()

        vm.heard("bhajan lagao")
        vm.onOverlayDismissed()
        assertEquals(VoicePhase.SEARCHING, vm.voice.value)

        // Well past the debounce window: a real dismiss, not a stray tap.
        clockMs += 700
        vm.onOverlayDismissed()

        assertEquals(VoicePhase.IDLE, vm.voice.value)
    }

    // SARASWATI: security-ask-explanation (fifth-round audit fix 1, blocks merge, confirmed by running it) —
    // the wall clock (System.currentTimeMillis()) can move backwards (NTP correction, a manual clock change);
    // a naive `nowMs() - explanationClosedAtMs < DISMISS_DEBOUNCE_MS` check would then go negative and satisfy
    // the debounce for as long as the gap, freezing tap-to-cancel for that whole time. The production default
    // is now elapsedRealtime() (monotonic, cannot do this), and the check itself also treats any negative
    // delta as "the window has passed" rather than "still inside it", so a backwards jump — simulated here via
    // the injectable nowMs, since this test cannot move the real clock — must not freeze cancellation.
    @Test
    fun `a clock that jumps backwards right after declining does not freeze tap-to-cancel`() {
        answer(AskIntent.PlayStation(bhajan.id))
        val explanationGate = FakeAskVoiceExplanationGate(seen = false)
        var clockMs = 10_000_000L
        val vm = newVm(explanationGate, nowMs = { clockMs })
        search.gate = CompletableDeferred()

        vm.heard("bhajan lagao")
        vm.onOverlayDismissed()
        assertEquals(VoicePhase.SEARCHING, vm.voice.value)

        // The clock jumps backwards by an hour (what an NTP correction or a manual clock change can do to
        // System.currentTimeMillis(), and what elapsedRealtime() by platform contract cannot).
        clockMs -= 3_600_000L
        vm.onOverlayDismissed()

        assertEquals("a backwards clock jump must not freeze tap-to-cancel", VoicePhase.IDLE, vm.voice.value)
    }

    // SARASWATI: security-ask-explanation (fifth-round audit fix 2, recommended, confirmed by running it) —
    // when Ask Saraswati is off, willReachHub() returns without a real suspension, so the whole surrounding
    // launch block (including the old searchFor's own nested voiceJob = launch { ... }) ran synchronously
    // end to end before the *outer* launch{} call returned and overwrote voiceJob back to that outer, already-
    // finished job — silently losing the reference to the real, still-running search. A cancel then cancelled
    // nothing. Checked through status, not just voice: voice.value flips to IDLE unconditionally inside
    // onOverlayDismissed regardless of whether the real coroutine was actually cancelled, so only the search
    // genuinely finishing (and reporting NOT_FOUND through status) would catch the bug; voice alone would not.
    @Test
    fun `cancelling a fallback search when Ask Saraswati is off actually stops it`() {
        hub.killSwitches = hub.killSwitches + (FamilyFeature.ASK_SARASWATI.key to false)
        runBlocking { gate.refresh() }
        val vm = newVm(explanationGate = FakeAskVoiceExplanationGate(seen = false))
        search.gate = CompletableDeferred()

        vm.heard("bhajan lagao")
        assertEquals(VoicePhase.SEARCHING, vm.voice.value)

        vm.onOverlayDismissed()
        assertEquals(VoicePhase.IDLE, vm.voice.value)

        // If the search were not actually cancelled, letting it finish here would report NOT_FOUND.
        search.gate?.complete(Unit)
        assertEquals(
            "the fallback search itself must be cancelled, not just a stale job reference",
            SaralStatus.Idle,
            vm.status.value,
        )
    }

    // SARASWATI: security-ask-explanation (sixth-round audit, same bug class as the fix just above) — runAsk's
    // own fallbacks (VoiceSearch, and PlayStation/ParivaarMix when the hub's answer doesn't resolve to
    // anything playable) called searchFor the same way the willReachHub()-false branch used to: a nested
    // `voiceJob = launch { ... }` that risks being overwritten by the surrounding launch{} returning right
    // after. On a real phone asker.resolve() suspends for real (the hub round trip) before reaching this, so
    // it did not bite there — but FakeAskHub/FixedIdentity in this very test file do not genuinely suspend
    // either, which is exactly how this reproduces here.
    @Test
    fun `cancelling a VoiceSearch fallback from runAsk actually stops it`() {
        answer(AskIntent.Answer("not a station or the mix"))
        val vm = newVm(explanationGate = FakeAskVoiceExplanationGate(seen = true))
        search.gate = CompletableDeferred()

        vm.heard("bhajan lagao")
        assertEquals(VoicePhase.SEARCHING, vm.voice.value)

        vm.onOverlayDismissed()
        assertEquals(VoicePhase.IDLE, vm.voice.value)

        // If the search were not actually cancelled, letting it finish here would report NOT_FOUND.
        search.gate?.complete(Unit)
        assertEquals(
            "runAsk's VoiceSearch fallback must actually be cancelled, not just a stale job reference",
            SaralStatus.Idle,
            vm.status.value,
        )
    }
}

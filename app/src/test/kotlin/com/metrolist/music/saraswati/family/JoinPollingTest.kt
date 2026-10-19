/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B1 (final review I3) — the Join screen's approval poll is bounded. It stops when
// the screen is paused, starts again when the screen resumes, and gives up after 15 minutes of one
// waiting session with a "Check again" button, instead of polling for as long as the process lives
// (about 14,000 requests a day per phone). Everything here runs on virtual time: nothing sleeps.
package com.metrolist.music.saraswati.family

import com.metrolist.music.saraswati.saral.GrandparentRoleSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JoinPollingTest {
    private val second = 1_000L
    private val minute = 60 * second
    private val interval = 6 * second
    private val cap = 15 * minute

    private lateinit var time: VirtualTimeDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeHub
    private lateinit var store: FakeStore
    private lateinit var inbox: PairingInbox
    private lateinit var vm: JoinFamilyViewModel

    private val waiting = JoinUiState.Waiting("CE-9")
    private val stillWaiting = JoinUiState.StillWaiting("CE-9")

    @Before
    fun setUp() {
        time = VirtualTimeDispatcher()
        scope = CoroutineScope(SupervisorJob() + time)
        hub = FakeHub().also { it.clockMs = { time.currentTime } }
        store = FakeStore().apply { claim = PendingClaim("device-9", "stored-secret") }
        inbox = PairingInbox()
        vm =
            JoinFamilyViewModel(
                hub,
                store,
                HubConfig(TEST_HUB_URL),
                inbox,
                GrandparentRoleSync(hub, store, FakeRoleStore(), noThrottle()),
                scope,
                pollIntervalMs = interval,
                maxWaitMs = cap,
                now = { time.currentTime },
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun polls() = hub.callsTo("pairingStatus").size

    private fun start() {
        vm.start()
        time.runCurrent()
    }

    // --- stop and start -------------------------------------------------------

    @Test
    fun `a stored claim is polled every interval while the screen is up`() {
        start()
        assertEquals(waiting, vm.state.value)
        assertEquals("the first poll waits one interval", 0, polls())

        time.advanceBy(interval)
        assertEquals(1, polls())
        time.advanceBy(interval)
        assertEquals(2, polls())
        assertEquals(listOf(interval, 2 * interval), hub.statusCallMs.toList())
    }

    @Test
    fun `nothing is polled after stop`() {
        start()
        time.advanceBy(interval)
        assertEquals(1, polls())

        vm.stop()
        time.advanceBy(30 * minute)

        assertEquals("no request after the screen was paused", 1, polls())
        assertEquals("the person comes back to the same screen", waiting, vm.state.value)
    }

    @Test
    fun `polling resumes on start after a stop`() {
        start()
        time.advanceBy(interval)
        vm.stop()
        time.advanceBy(10 * minute)
        assertEquals(1, polls())

        start()
        assertEquals(waiting, vm.state.value)
        time.advanceBy(interval)

        assertEquals(2, polls())
    }

    @Test
    fun `repeated starts and stops never run two polls at once`() {
        start()
        start()
        vm.stop()
        vm.stop()
        start()
        start()

        time.advanceBy(interval)

        assertEquals(1, polls())
    }

    @Test
    fun `stop with nothing to stop is harmless`() {
        vm.stop()
        vm.stop()

        assertEquals(JoinUiState.Idle, vm.state.value)
        assertEquals(0, polls())
    }

    @Test
    fun `a claim that finishes while the screen is paused waits for the screen to come back`() {
        store.claim = null
        val inFlight = CompletableDeferred<Unit>()
        hub.claimGate = inFlight
        vm.submitInput("$TEST_HUB_URL/pair#$TEST_TOKEN")
        vm.claim("Mummy's Pixel", DeviceRole.ADULT)
        time.runCurrent()
        assertEquals(JoinUiState.Claiming, vm.state.value)

        vm.stop()
        inFlight.complete(Unit)
        time.runCurrent()
        time.advanceBy(10 * minute)

        assertEquals(JoinUiState.Waiting("CE-1"), vm.state.value)
        assertEquals("the claim is kept, the poll is not started behind a paused screen", 0, polls())
        assertEquals(PendingClaim("device-1", "claim-secret-device-1"), store.claim)

        start()
        time.advanceBy(interval)
        assertEquals(1, polls())
    }

    @Test
    fun `an approval that comes in is kept, and a later stop does not undo it`() {
        hub.scriptStatus(PairingStatus.Approved("the-session-token"))
        start()

        time.advanceBy(interval)
        time.runCurrent()
        vm.stop()
        time.advanceBy(minute)

        assertEquals(JoinUiState.Approved(DeviceRole.ADULT), vm.state.value)
        assertEquals(FamilySession("device-9", "the-session-token"), store.session)
        assertEquals("the poll ended with the approval", 1, polls())
    }

    // --- the 15-minute cap ----------------------------------------------------

    @Test
    fun `one waiting session ends after fifteen minutes with a still-waiting state`() {
        start()

        time.advanceBy(cap - 1)
        assertEquals("still inside the cap", waiting, vm.state.value)

        time.advanceBy(1)
        assertEquals(stillWaiting, vm.state.value)
        val atCap = polls()
        assertTrue("polled at every interval up to the cap, not past it: $atCap", atCap in 140..150)

        time.advanceBy(2 * 60 * minute)
        assertEquals("no request after the cap", atCap, polls())
        assertEquals(stillWaiting, vm.state.value)
    }

    @Test
    fun `the cap holds while the hub keeps answering 429`() {
        repeat(40) { hub.scriptStatus(hubFailure(429)) }
        start()

        time.advanceBy(cap)

        assertEquals(stillWaiting, vm.state.value)
        val atCap = polls()
        time.advanceBy(60 * minute)
        assertEquals(atCap, polls())
    }

    @Test
    fun `coming back to a screen that gave up does not poll by itself`() {
        start()
        time.advanceBy(cap)
        assertEquals(stillWaiting, vm.state.value)
        val atCap = polls()

        vm.stop()
        start()
        time.advanceBy(10 * minute)

        assertEquals(stillWaiting, vm.state.value)
        assertEquals(atCap, polls())
    }

    @Test
    fun `check again asks once at once, then waits a fresh fifteen minutes`() {
        start()
        time.advanceBy(cap)
        val atCap = polls()

        vm.checkAgain()
        time.runCurrent()

        assertEquals(waiting, vm.state.value)
        assertEquals("the button gets an answer without waiting an interval", atCap + 1, polls())

        time.advanceBy(cap - 1)
        assertEquals(waiting, vm.state.value)
        time.advanceBy(1)
        assertEquals(stillWaiting, vm.state.value)
    }

    @Test
    fun `check again finds the approval`() {
        start()
        time.advanceBy(cap)
        hub.scriptStatus(PairingStatus.Approved("late-session"))

        vm.checkAgain()
        time.runCurrent()

        assertEquals(JoinUiState.Approved(DeviceRole.ADULT), vm.state.value)
        assertEquals(FamilySession("device-9", "late-session"), store.session)
        assertNull(store.claim)
    }

    @Test
    fun `check again when the claim is gone from storage ends the wait`() {
        start()
        time.advanceBy(cap)
        store.claim = null

        vm.checkAgain()
        time.runCurrent()

        assertEquals(JoinUiState.Idle, vm.state.value)
    }

    @Test
    fun `check again does nothing unless the screen is saying it is still waiting`() {
        start()

        vm.checkAgain()
        time.runCurrent()

        assertEquals(waiting, vm.state.value)
        assertEquals(0, polls())
    }

    @Test
    fun `every start after a stop is a new waiting session with its own fifteen minutes`() {
        start()
        time.advanceBy(10 * minute)
        vm.stop()
        start()

        time.advanceBy(10 * minute)

        assertEquals("twenty minutes in all, but never fifteen in one session", waiting, vm.state.value)
    }

    // --- 429 backoff ----------------------------------------------------------

    @Test
    fun `each 429 doubles the wait up to a minute, and an answer brings it back`() {
        repeat(5) { hub.scriptStatus(hubFailure(429)) }
        hub.scriptStatus(PairingStatus.Pending)
        start()

        time.advanceBy(6 * minute)

        // 6 s, then 12, 24, 48 and 60 (96 capped) after each of the five 429s, then back to 6 after an answer.
        assertEquals(
            listOf(6, 18, 42, 90, 150, 210, 216, 222).map { it * second },
            hub.statusCallMs.toList().take(8),
        )
    }

    @Test
    fun `offline or a server error keeps the interval, a 404 ends the wait`() {
        hub.scriptStatus(OFFLINE, hubFailure(500))
        start()

        time.advanceBy(2 * interval)
        assertEquals(listOf(interval, 2 * interval), hub.statusCallMs.toList())
        assertEquals(waiting, vm.state.value)

        hub.scriptStatus(hubFailure(404))
        time.advanceBy(interval)
        assertEquals(JoinUiState.Failed(FamilyError.NOT_FOUND), vm.state.value)
        assertNull(store.claim)
        time.advanceBy(10 * minute)
        assertEquals(3, polls())
    }
}

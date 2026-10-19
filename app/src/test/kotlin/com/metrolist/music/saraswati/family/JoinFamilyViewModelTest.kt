/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the claiming phone's side of pairing: take a link,
// claim it with the chosen role, poll until the owner approves, keep the
// session. The poll tests run on a real dispatcher with a 1 ms interval;
// everything else finishes synchronously.
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JoinFamilyViewModelTest {
    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeHub
    private lateinit var store: FakeStore
    private lateinit var inbox: PairingInbox
    private lateinit var vm: JoinFamilyViewModel

    private val link = "$TEST_HUB_URL/pair#$TEST_TOKEN"

    private fun newVm(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined): JoinFamilyViewModel {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        return JoinFamilyViewModel(hub, store, HubConfig(TEST_HUB_URL), inbox, scope, pollIntervalMs = 1)
    }

    private fun awaitState(predicate: (JoinUiState) -> Boolean): JoinUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Before
    fun setUp() {
        hub = FakeHub()
        store = FakeStore()
        inbox = PairingInbox()
        vm = newVm()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `starts idle when there is nothing to join`() {
        vm.start()

        assertEquals(JoinUiState.Idle, vm.state.value)
    }

    @Test
    fun `a link from the inbox becomes the token to claim`() {
        inbox.offer(TEST_TOKEN)

        vm.start()

        assertEquals(JoinUiState.Ready(TEST_TOKEN), vm.state.value)
        assertNull(inbox.take())
    }

    @Test
    fun `a pasted link or token becomes the token to claim`() {
        vm.submitInput("  $link ")
        assertEquals(JoinUiState.Ready(TEST_TOKEN), vm.state.value)

        vm = newVm()
        vm.submitInput(TEST_TOKEN)
        assertEquals(JoinUiState.Ready(TEST_TOKEN), vm.state.value)
    }

    @Test
    fun `pasted text that is not this family's pairing link is refused`() {
        vm.submitInput("https://evil.example.com/pair#$TEST_TOKEN")
        assertEquals(JoinUiState.Failed(FamilyError.INVALID_INPUT), vm.state.value)

        vm.submitInput("hello")
        assertEquals(JoinUiState.Failed(FamilyError.INVALID_INPUT), vm.state.value)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun `claim sends the token, the trimmed name and the chosen role, then waits`() {
        vm.submitInput(link)

        vm.claim("  Mummy's Pixel ", DeviceRole.GRANDPARENT)

        assertEquals(listOf("claimPairing($TEST_TOKEN,Mummy's Pixel,grandparent)"), hub.callsTo("claimPairing"))
        assertEquals(JoinUiState.Waiting, vm.state.value)
        assertEquals(PendingClaim("device-1", "claim-secret-device-1"), store.claim)
    }

    @Test
    fun `a long name is cut to the length the hub shows`() {
        vm.submitInput(link)

        vm.claim("x".repeat(100), DeviceRole.ADULT)

        assertEquals(JoinFamilyViewModel.MAX_NAME_LENGTH, hub.devices.single().deviceName!!.length)
    }

    @Test
    fun `claim without a link or with a blank name never calls the hub`() {
        vm.claim("Mummy's Pixel", DeviceRole.ADULT)
        assertEquals(JoinUiState.Failed(FamilyError.INVALID_INPUT), vm.state.value)

        vm.submitInput(link)
        vm.claim("   ", DeviceRole.ADULT)
        assertEquals(JoinUiState.Failed(FamilyError.INVALID_INPUT), vm.state.value)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun `a claim the hub answers 410 or 404 drops the token`() {
        listOf(410 to FamilyError.GONE, 404 to FamilyError.NOT_FOUND).forEach { (status, expected) ->
            vm = newVm()
            hub.failWith("claimPairing", hubFailure(status))
            vm.submitInput(link)

            vm.claim("Mummy's Pixel", DeviceRole.ADULT)

            assertEquals(JoinUiState.Failed(expected), vm.state.value)
            vm.claim("Mummy's Pixel", DeviceRole.ADULT)
            assertEquals(JoinUiState.Failed(FamilyError.INVALID_INPUT), vm.state.value)
            assertNull(store.claim)
        }
    }

    @Test
    fun `a claim that fails for a passing reason keeps the token so it can be retried`() {
        hub.failWith("claimPairing", hubFailure(429))
        vm.submitInput(link)
        vm.claim("Mummy's Pixel", DeviceRole.ADULT)
        assertEquals(JoinUiState.Failed(FamilyError.RATE_LIMITED), vm.state.value)

        hub.failWith("claimPairing", OFFLINE)
        vm.claim("Mummy's Pixel", DeviceRole.ADULT)
        assertEquals(JoinUiState.Failed(FamilyError.OFFLINE), vm.state.value)

        hub.heal("claimPairing")
        vm.claim("Mummy's Pixel", DeviceRole.ADULT)
        assertEquals(JoinUiState.Waiting, vm.state.value)
    }

    @Test
    fun `polling until approved stores the session, confirms it and reports the role`() {
        hub.meRole = DeviceRole.GRANDPARENT
        hub.scriptStatus(
            PairingStatus.Pending,
            OFFLINE,
            hubFailure(429),
            PairingStatus.Approved("the-session-token"),
        )
        vm = newVm(Dispatchers.Default)
        vm.submitInput(link)

        vm.claim("Mummy's Pixel", DeviceRole.GRANDPARENT)

        assertEquals(JoinUiState.Approved(DeviceRole.GRANDPARENT), awaitState { it is JoinUiState.Approved })
        assertEquals(FamilySession("device-1", "the-session-token"), store.session)
        assertNull(store.claim)
        assertEquals(listOf("getMe(the-session-token)"), hub.callsTo("getMe"))
        assertEquals(4, hub.callsTo("pairingStatus").size)
        assertTrue(hub.callsTo("pairingStatus").all { it == "pairingStatus(device-1,claim-secret-device-1)" })
    }

    @Test
    fun `if the role check fails after approval the phone is still paired with the role it chose`() {
        hub.failWith("getMe", OFFLINE)
        hub.scriptStatus(PairingStatus.Approved("tok"))
        vm = newVm(Dispatchers.Default)
        vm.submitInput(link)

        vm.claim("Papa's Samsung", DeviceRole.ADULT)

        assertEquals(JoinUiState.Approved(DeviceRole.ADULT), awaitState { it is JoinUiState.Approved })
        assertEquals("tok", store.session?.sessionToken)
    }

    @Test
    fun `a claim the hub no longer knows ends the wait`() {
        hub.scriptStatus(hubFailure(404))
        vm = newVm(Dispatchers.Default)
        vm.submitInput(link)

        vm.claim("Mummy's Pixel", DeviceRole.ADULT)

        assertEquals(JoinUiState.Failed(FamilyError.NOT_FOUND), awaitState { it is JoinUiState.Failed })
        assertNull(store.claim)
        assertNull(store.session)
    }

    @Test
    fun `a stored claim resumes waiting after a restart`() {
        store.claim = PendingClaim("device-9", "stored-secret")
        hub.scriptStatus(PairingStatus.Pending, PairingStatus.Approved("tok9"))
        vm = newVm(Dispatchers.Default)

        vm.start()

        assertEquals(JoinUiState.Approved(DeviceRole.ADULT), awaitState { it is JoinUiState.Approved })
        assertEquals(FamilySession("device-9", "tok9"), store.session)
        assertEquals("pairingStatus(device-9,stored-secret)", hub.callsTo("pairingStatus").first())
    }

    @Test
    fun `a phone that already has a session is already paired and ignores a new link`() {
        store.session = FamilySession("device-1", "tok")
        inbox.offer(TEST_TOKEN)

        vm.start()

        assertEquals(JoinUiState.AlreadyPaired, vm.state.value)
        assertNull(inbox.take())
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun `an owner phone is not offered a join`() {
        store.admin = TEST_ADMIN_KEY
        inbox.offer(TEST_TOKEN)

        vm.start()

        assertEquals(JoinUiState.AlreadyPaired, vm.state.value)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun `a storage failure after a claim is an error, not a crash`() {
        vm.submitInput(link)
        store.failWrites = java.io.IOException("disk full")

        vm.claim("Mummy's Pixel", DeviceRole.ADULT)

        assertEquals(JoinUiState.Failed(FamilyError.UNKNOWN), vm.state.value)
    }

    @Test
    fun `the state never prints the token, the claim secret or the session`() {
        hub.scriptStatus(PairingStatus.Approved("the-session-token"))
        vm = newVm(Dispatchers.Default)
        vm.submitInput(link)
        val ready = vm.state.value.toString()
        vm.claim("Mummy's Pixel", DeviceRole.ADULT)
        awaitState { it is JoinUiState.Approved }

        assertFalse(ready.contains(TEST_TOKEN))
        assertFalse(store.claim.toString().contains("claim-secret"))
        assertFalse(store.session.toString().contains("the-session-token"))
        assertFalse(PendingClaim("d", "claim-secret-x").toString().contains("claim-secret"))
    }
}

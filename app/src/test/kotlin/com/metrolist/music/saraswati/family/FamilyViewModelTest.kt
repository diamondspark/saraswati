/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — FamilyViewModel against a fake hub and an in-memory
// credential store. Unconfined dispatching makes each call finish before it
// returns, because the fakes never suspend.
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FamilyViewModelTest {
    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeHub
    private lateinit var store: FakeStore
    private lateinit var vm: FamilyViewModel

    private val mummy = RosterEntry("d1", "Mummy's Pixel", "pending", null, DeviceRole.GRANDPARENT)
    private val papa = RosterEntry("d2", "Papa's Samsung", "approved", 5L, DeviceRole.ADULT)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        hub = FakeHub()
        store = FakeStore(admin = TEST_ADMIN_KEY)
        vm = FamilyViewModel(hub, store, HubConfig(TEST_HUB_URL), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `starts idle with nothing loaded and no error`() {
        assertEquals(PairingUiState.Idle, vm.pairingState.value)
        assertTrue(vm.roster.value.isEmpty())
        assertTrue(vm.health.value.isEmpty())
        assertNull(vm.error.value)
    }

    @Test
    fun `startPairing surfaces the token and a link a phone can open`() {
        vm.startPairing()

        val state = vm.pairingState.value as PairingUiState.Ready
        assertEquals(TEST_TOKEN, state.token)
        assertEquals("$TEST_HUB_URL/pair#$TEST_TOKEN", state.link)
        assertEquals("2026-10-19T18:27:00.000Z", state.expiresAt)
        assertEquals(listOf("createPairing($TEST_ADMIN_KEY)"), hub.calls)
    }

    @Test
    fun `startPairing without the admin key never calls the hub`() {
        store.admin = null

        vm.startPairing()

        assertEquals(PairingUiState.Error(FamilyError.NO_ADMIN_KEY), vm.pairingState.value)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun `a hub failure while pairing is an error state, never a crash`() {
        mapOf(
            hubFailure(401) to FamilyError.UNAUTHORIZED,
            hubFailure(429) to FamilyError.RATE_LIMITED,
            hubFailure(500) to FamilyError.SERVER,
            OFFLINE to FamilyError.OFFLINE,
            IllegalStateException("hub base URL is not configured") to FamilyError.NOT_CONFIGURED,
            RuntimeException("boom") to FamilyError.UNKNOWN,
        ).forEach { (failure, expected) ->
            hub.failWith("createPairing", failure)
            vm.startPairing()
            assertEquals(failure.toString(), PairingUiState.Error(expected), vm.pairingState.value)
        }
    }

    @Test
    fun `a token the link builder cannot use is an error, not a broken link`() {
        hub.pairingToken = PairingToken("not-hex", "2026-10-19T18:27:00.000Z")

        vm.startPairing()

        assertEquals(PairingUiState.Error(FamilyError.UNKNOWN), vm.pairingState.value)
    }

    @Test
    fun `an error state clears when pairing is started again`() {
        hub.failWith("createPairing", OFFLINE)
        vm.startPairing()
        hub.heal("createPairing")

        vm.startPairing()

        assertTrue(vm.pairingState.value is PairingUiState.Ready)
    }

    @Test
    fun `refreshRoster loads the roster with the admin key`() {
        hub.devices = listOf(mummy, papa)

        vm.refreshRoster()

        assertEquals(listOf(mummy, papa), vm.roster.value)
        assertEquals(listOf("getRoster($TEST_ADMIN_KEY)"), hub.calls)
    }

    @Test
    fun `a failed refresh keeps the roster already shown and reports the error`() {
        hub.devices = listOf(mummy)
        vm.refreshRoster()
        hub.failWith("getRoster", OFFLINE)

        vm.refreshRoster()

        assertEquals(listOf(mummy), vm.roster.value)
        assertEquals(FamilyError.OFFLINE, vm.error.value)
    }

    @Test
    fun `approve calls through and refreshes the roster`() {
        hub.devices = listOf(mummy)
        vm.refreshRoster()

        vm.approve("d1")

        assertEquals(listOf("approveDevice($TEST_ADMIN_KEY,d1)"), hub.callsTo("approveDevice"))
        assertEquals("approved", vm.roster.value.single().status)
        assertNull(vm.error.value)
    }

    @Test
    fun `revoke calls through and refreshes the roster`() {
        hub.devices = listOf(papa)
        vm.refreshRoster()

        vm.revoke("d2")

        assertEquals(listOf("revokeDevice($TEST_ADMIN_KEY,d2)"), hub.callsTo("revokeDevice"))
        assertEquals("revoked", vm.roster.value.single().status)
    }

    @Test
    fun `a failed approve is an error, leaves the roster alone and does not refresh`() {
        hub.devices = listOf(mummy)
        vm.refreshRoster()
        hub.failWith("approveDevice", hubFailure(404))

        vm.approve("d1")

        assertEquals(FamilyError.NOT_FOUND, vm.error.value)
        assertEquals("pending", vm.roster.value.single().status)
        assertEquals(1, hub.callsTo("getRoster").size)
    }

    @Test
    fun `a failed revoke is an error`() {
        hub.devices = listOf(papa)
        vm.refreshRoster()
        hub.failWith("revokeDevice", hubFailure(401))

        vm.revoke("d2")

        assertEquals(FamilyError.UNAUTHORIZED, vm.error.value)
        assertEquals("approved", vm.roster.value.single().status)
    }

    @Test
    fun `an approve that worked but whose refresh failed still reports the failure`() {
        hub.devices = listOf(mummy)
        vm.refreshRoster()
        hub.failWith("getRoster", OFFLINE)

        vm.approve("d1")

        assertEquals(1, hub.callsTo("approveDevice").size)
        assertEquals(FamilyError.OFFLINE, vm.error.value)
    }

    @Test
    fun `setRole switches a phone between adult and grandparent and refreshes`() {
        hub.devices = listOf(mummy, papa)
        vm.refreshRoster()

        vm.setRole("d1", DeviceRole.ADULT)
        vm.setRole("d2", DeviceRole.GRANDPARENT)

        assertEquals(
            listOf("setRole($TEST_ADMIN_KEY,d1,adult)", "setRole($TEST_ADMIN_KEY,d2,grandparent)"),
            hub.callsTo("setRole"),
        )
        assertEquals(listOf(DeviceRole.ADULT, DeviceRole.GRANDPARENT), vm.roster.value.map { it.role })
    }

    @Test
    fun `a failed setRole is an error and the roster keeps the old role`() {
        hub.devices = listOf(mummy)
        vm.refreshRoster()
        hub.failWith("setRole", hubFailure(404))

        vm.setRole("d1", DeviceRole.ADULT)

        assertEquals(FamilyError.NOT_FOUND, vm.error.value)
        assertEquals(DeviceRole.GRANDPARENT, vm.roster.value.single().role)
    }

    @Test
    fun `every owner action without the admin key reports it and never calls the hub`() {
        store.admin = null

        vm.refreshRoster()
        vm.refreshHealth()
        vm.approve("d1")
        vm.revoke("d1")
        vm.setRole("d1", DeviceRole.ADULT)

        assertEquals(FamilyError.NO_ADMIN_KEY, vm.error.value)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun `refreshHealth loads each phone's report`() {
        val report = DeviceHealth("d2", "Papa's Samsung", 3902, "1.4.0", "Asia/Kolkata", 1_700_000_000_000L)
        hub.health = listOf(report)

        vm.refreshHealth()

        assertEquals(listOf(report), vm.health.value)
        assertEquals(listOf("getHealth($TEST_ADMIN_KEY)"), hub.calls)
    }

    @Test
    fun `a failed health refresh keeps what was shown and reports the error`() {
        val report = DeviceHealth("d2", "Papa's Samsung", 3902, "1.4.0", "Asia/Kolkata", 1L)
        hub.health = listOf(report)
        vm.refreshHealth()
        hub.failWith("getHealth", hubFailure(429))

        vm.refreshHealth()

        assertEquals(listOf(report), vm.health.value)
        assertEquals(FamilyError.RATE_LIMITED, vm.error.value)
    }

    @Test
    fun `refreshOwnerData loads the roster and the health view`() {
        hub.devices = listOf(papa)

        vm.refreshOwnerData()

        assertEquals(listOf("getRoster($TEST_ADMIN_KEY)", "getHealth($TEST_ADMIN_KEY)"), hub.calls)
    }

    @Test
    fun `a success clears an earlier error and clearError dismisses one`() {
        hub.failWith("getRoster", OFFLINE)
        vm.refreshRoster()
        assertEquals(FamilyError.OFFLINE, vm.error.value)
        hub.heal("getRoster")

        vm.refreshRoster()
        assertNull(vm.error.value)

        hub.failWith("getRoster", OFFLINE)
        vm.refreshRoster()
        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun `a storage failure is an error, never a crash`() {
        store.failWrites = java.io.IOException("disk full")

        vm.saveAdminKey("new-admin-key")

        assertEquals(OwnerSetupState.Failed(FamilyError.UNKNOWN), vm.setupState.value)
    }

    @Test
    fun `saveAdminKey checks the key against the hub before keeping it`() {
        store.admin = null

        vm.saveAdminKey("  new-admin-key \n")

        assertEquals(OwnerSetupState.Done, vm.setupState.value)
        assertEquals(listOf("getRoster(new-admin-key)"), hub.calls)
        assertEquals("new-admin-key", store.admin)
        assertTrue(store.owner)
    }

    @Test
    fun `saving a working key clears the missing-key error that sent the screen to setup`() {
        store.admin = null
        vm.refreshRoster()
        assertEquals(FamilyError.NO_ADMIN_KEY, vm.error.value)

        vm.saveAdminKey("the-admin-key")

        assertEquals(OwnerSetupState.Done, vm.setupState.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `saveAdminKey with a key the hub refuses stores nothing`() {
        store.admin = null
        hub.failWith("getRoster", hubFailure(401))

        vm.saveAdminKey("wrong-key")

        assertEquals(OwnerSetupState.Failed(FamilyError.UNAUTHORIZED), vm.setupState.value)
        assertNull(store.admin)
        assertFalse(store.owner)
    }

    @Test
    fun `saveAdminKey with a blank key is rejected without calling the hub`() {
        store.admin = null

        vm.saveAdminKey("   ")

        assertEquals(OwnerSetupState.Failed(FamilyError.INVALID_INPUT), vm.setupState.value)
        assertTrue(hub.calls.isEmpty())
        assertFalse(store.owner)
    }

    @Test
    fun `saveAdminKey with an unreachable hub stores nothing`() {
        store.admin = null
        hub.failWith("getRoster", OFFLINE)

        vm.saveAdminKey("some-key")

        assertEquals(OwnerSetupState.Failed(FamilyError.OFFLINE), vm.setupState.value)
        assertNull(store.admin)
    }

    @Test
    fun `a phone with a stored session is a member, and one without is not`() {
        vm.refreshMembership()
        assertFalse(vm.isMember.value)

        store.session = FamilySession("d9", "tok")
        vm.refreshMembership()
        assertTrue(vm.isMember.value)
    }

    @Test
    fun `a phone that only has a pending claim is not yet a member`() {
        store.claim = PendingClaim("d9", "secret")

        vm.refreshMembership()

        assertFalse(vm.isMember.value)
    }

    @Test
    fun `error mapping covers the hub's statuses and transport failures`() {
        assertEquals(FamilyError.INVALID_INPUT, FamilyError.from(hubFailure(400)))
        assertEquals(FamilyError.UNAUTHORIZED, FamilyError.from(hubFailure(401)))
        assertEquals(FamilyError.NOT_FOUND, FamilyError.from(hubFailure(404)))
        assertEquals(FamilyError.GONE, FamilyError.from(hubFailure(410)))
        assertEquals(FamilyError.RATE_LIMITED, FamilyError.from(hubFailure(429)))
        assertEquals(FamilyError.SERVER, FamilyError.from(hubFailure(503)))
        assertEquals(FamilyError.OFFLINE, FamilyError.from(java.net.SocketTimeoutException()))
        assertEquals(FamilyError.INVALID_INPUT, FamilyError.from(IllegalArgumentException("invalid device id")))
        assertEquals(FamilyError.UNKNOWN, FamilyError.from(RuntimeException()))
    }

    @Test
    fun `state strings never contain the admin key or a session token`() {
        hub.devices = listOf(mummy)
        vm.refreshRoster()
        vm.approve("d1")

        val everything = listOf(vm.pairingState.value, vm.roster.value, vm.health.value, vm.error.value, vm.setupState.value)
        assertFalse(everything.toString().contains(TEST_ADMIN_KEY))
        assertFalse(everything.toString().contains("session-d1"))
    }
}

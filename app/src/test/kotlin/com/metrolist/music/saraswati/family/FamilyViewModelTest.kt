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
    private lateinit var cache: FakeStationsCache
    private lateinit var vm: FamilyViewModel
    private var newIds = 0

    private val mummy = RosterEntry("d1", "Mummy's Pixel", "pending", null, DeviceRole.GRANDPARENT)
    private val papa = RosterEntry("d2", "Papa's Samsung", "approved", 5L, DeviceRole.ADULT)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        hub = FakeHub()
        store = FakeStore(admin = TEST_ADMIN_KEY)
        cache = FakeStationsCache()
        newIds = 0
        vm = FamilyViewModel(hub, store, HubConfig(TEST_HUB_URL), cache, scope, newStationId = { "new-${++newIds}" })
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
        // The session token the hub returns belongs to the claiming phone; the owner's keeps none.
        assertNull(store.session)
        assertNull(store.claim)
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

    // SARASWATI: fix wave A (M7) — the screen clears the clipboard once, and only after, the key was kept.
    @Test
    fun `onSaved runs once with the trimmed key after the key was kept`() {
        store.admin = null
        val saved = mutableListOf<String>()

        vm.saveAdminKey("  new-admin-key \n") { saved.add(it) }

        assertEquals(listOf("new-admin-key"), saved)
        assertEquals("new-admin-key", store.admin)
    }

    @Test
    fun `onSaved never runs when the hub refuses the key, the key is blank, or storing it fails`() {
        store.admin = null
        val saved = mutableListOf<String>()

        vm.saveAdminKey("   ") { saved.add(it) }
        hub.failWith("getRoster", hubFailure(401))
        vm.saveAdminKey("wrong-key") { saved.add(it) }
        hub.heal("getRoster")
        store.failWrites = java.io.IOException("disk full")
        vm.saveAdminKey("right-key") { saved.add(it) }

        assertTrue(saved.toString(), saved.isEmpty())
        assertEquals(OwnerSetupState.Failed(FamilyError.UNKNOWN), vm.setupState.value)
    }

    @Test
    fun `a callback that throws cannot undo a saved key`() {
        store.admin = null

        vm.saveAdminKey("the-admin-key") { error("clipboard exploded") }

        assertEquals(OwnerSetupState.Done, vm.setupState.value)
        assertEquals("the-admin-key", store.admin)
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

    // SARASWATI: fix wave A (M2) — the Family tab hides owner setup while a claim waits for approval.
    @Test
    fun `a phone with a stored claim and no session reports a pending claim`() {
        vm.refreshMembership()
        assertFalse(vm.hasPendingClaim.value)

        store.claim = PendingClaim("d9", "secret")
        vm.refreshMembership()
        assertTrue(vm.hasPendingClaim.value)
        assertFalse(vm.isMember.value)
    }

    @Test
    fun `approval ends the pending claim`() {
        store.claim = PendingClaim("d9", "secret")
        vm.refreshMembership()
        assertTrue(vm.hasPendingClaim.value)

        store.session = FamilySession("d9", "tok")
        store.claim = null
        vm.refreshMembership()

        assertFalse(vm.hasPendingClaim.value)
        assertTrue(vm.isMember.value)
    }

    @Test
    fun `a storage error reads as no pending claim and does not throw`() {
        store.claim = PendingClaim("d9", "secret")
        store.failReads = java.io.IOException("keystore unavailable")

        vm.refreshMembership()

        assertFalse(vm.hasPendingClaim.value)
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
    // --- Task 8: stations and kill-switches --------------------------------

    private fun video(n: Int) = "video" + n.toString().padStart(6, '0')

    private fun station(
        n: Int,
        icon: Int = 0,
    ) = Station("s$n", "Station $n", video(n), icon)

    private fun hubWith(count: Int) {
        hub.stations = (1..count).map { station(it) }
        vm.loadSettings()
    }

    private val stationCalls get() = hub.callsTo("setStations")

    @Test
    fun `an empty hub list starts the editor on the four defaults, which the hub has not been given`() {
        hub.stations = emptyList()

        vm.loadSettings()

        assertEquals(
            listOf("Bollywood Classics", "Bhajans & Devotional", "Ghazals & Old Melodies", "Peaceful & Meditation"),
            vm.stations.value.map { it.title },
        )
        assertEquals(4, vm.stations.value.map { it.id }.toSet().size)
        assertEquals(StationsState.DRAFT, vm.stationsState.value)
        assertTrue(stationCalls.isEmpty())
    }

    @Test
    fun `the defaults leave two of the six slots free and need a video each before they are saved`() {
        vm.loadSettings()

        assertEquals(4, vm.stations.value.size)
        assertTrue(vm.stations.value.size < FamilyViewModel.MAX_STATIONS)
        assertTrue(vm.stations.value.all { it.seedVideoId.isEmpty() })
    }

    @Test
    fun `a list from the hub is shown as it is`() {
        hubWith(3)

        assertEquals((1..3).map { station(it) }, vm.stations.value)
        assertEquals(StationsState.SYNCED, vm.stationsState.value)
    }

    @Test
    fun `adding a station sends the whole resulting list`() {
        hubWith(2)

        vm.addStation("Ghazals", "https://youtu.be/dQw4w9WgXcQ", iconIndex = 3)

        assertEquals(1, hub.callsTo("getStations").size)
        assertEquals(listOf("setStations($TEST_ADMIN_KEY,s1|s2|new-1)"), stationCalls)
        assertEquals(
            listOf(station(1), station(2), Station("new-1", "Ghazals", "dQw4w9WgXcQ", 3)),
            vm.stations.value,
        )
        assertEquals(StationsState.SYNCED, vm.stationsState.value)
        assertEquals(hub.stations, vm.stations.value)
    }

    @Test
    fun `editing a station replaces it in place and sends the whole list`() {
        hubWith(3)

        vm.updateStation("s2", "  Bhajans  ", "https://youtu.be/dQw4w9WgXcQ", iconIndex = 2)

        assertEquals(listOf("setStations($TEST_ADMIN_KEY,s1|s2|s3)"), stationCalls)
        assertEquals(Station("s2", "Bhajans", "dQw4w9WgXcQ", 2), vm.stations.value[1])
        assertEquals(station(1), vm.stations.value[0])
        assertEquals(station(3), vm.stations.value[2])
    }

    @Test
    fun `a blank video field keeps the station's current video`() {
        hubWith(2)

        vm.updateStation("s1", "Renamed", "", iconIndex = 4)

        assertEquals(Station("s1", "Renamed", video(1), 4), vm.stations.value[0])
        assertEquals(listOf("setStations($TEST_ADMIN_KEY,s1|s2)"), stationCalls)
    }

    @Test
    fun `a starting default can be renamed before it has a video and stays a draft`() {
        vm.loadSettings()
        val id = vm.stations.value.first().id

        vm.updateStation(id, "Mummy's Songs", "", iconIndex = 2)

        assertEquals(Station(id, "Mummy's Songs", "", 2), vm.stations.value.first())
        assertEquals(StationsState.DRAFT, vm.stationsState.value)
        assertTrue(stationCalls.isEmpty())
        assertNull(vm.error.value)
    }

    @Test
    fun `removing a station sends the list without it`() {
        hubWith(3)

        vm.removeStation("s2")

        assertEquals(listOf("setStations($TEST_ADMIN_KEY,s1|s3)"), stationCalls)
        assertEquals(listOf("s1", "s3"), vm.stations.value.map { it.id })
    }

    @Test
    fun `moving a station up or down sends the reordered list`() {
        hubWith(3)

        vm.moveStation("s3", -1)
        assertEquals("setStations($TEST_ADMIN_KEY,s1|s3|s2)", stationCalls.last())

        vm.moveStation("s1", +1)
        assertEquals("setStations($TEST_ADMIN_KEY,s3|s1|s2)", stationCalls.last())
        assertEquals(listOf("s3", "s1", "s2"), vm.stations.value.map { it.id })
    }

    @Test
    fun `moving past either end does nothing and calls nothing`() {
        hubWith(3)

        vm.moveStation("s1", -1)
        vm.moveStation("s3", +1)
        vm.moveStation("missing", +1)

        assertTrue(stationCalls.isEmpty())
        assertEquals((1..3).map { station(it) }, vm.stations.value)
    }

    @Test
    fun `a seventh station is refused on the spot, without asking the hub`() {
        hubWith(6)

        vm.addStation("One too many", "dQw4w9WgXcQ", 0)

        assertEquals(FamilyError.STATION_LIMIT, vm.error.value)
        assertTrue(stationCalls.isEmpty())
        assertEquals(6, vm.stations.value.size)
    }

    @Test
    fun `the last station cannot be removed`() {
        hubWith(1)

        vm.removeStation("s1")

        assertEquals(FamilyError.STATION_LIMIT, vm.error.value)
        assertTrue(stationCalls.isEmpty())
        assertEquals(1, vm.stations.value.size)
    }

    @Test
    fun `setStations itself enforces one to six`() {
        hubWith(2)

        vm.setStations(emptyList())
        assertEquals(FamilyError.STATION_LIMIT, vm.error.value)

        vm.clearError()
        vm.setStations((1..7).map { station(it) })
        assertEquals(FamilyError.STATION_LIMIT, vm.error.value)

        assertTrue(stationCalls.isEmpty())
        assertEquals(2, vm.stations.value.size)
    }

    @Test
    fun `setStations sends exactly the list it is given`() {
        hubWith(1)

        vm.setStations(listOf(station(5), station(4)))

        assertEquals(listOf("setStations($TEST_ADMIN_KEY,s5|s4)"), stationCalls)
        assertEquals(listOf("s5", "s4"), vm.stations.value.map { it.id })
    }

    @Test
    fun `six stations is allowed`() {
        hubWith(5)

        vm.addStation("Sixth", "dQw4w9WgXcQ", 1)

        assertEquals(6, vm.stations.value.size)
        assertEquals(1, stationCalls.size)
    }

    @Test
    fun `a station whose video cannot be resolved is refused before anything is sent`() {
        hubWith(2)

        vm.addStation("Bad", "https://example.com/not-youtube", 0)
        assertEquals(FamilyError.INVALID_INPUT, vm.error.value)

        vm.clearError()
        vm.updateStation("s1", "Bad", "nonsense", 0)
        assertEquals(FamilyError.INVALID_INPUT, vm.error.value)

        vm.clearError()
        vm.addStation("   ", "dQw4w9WgXcQ", 0)
        assertEquals(FamilyError.INVALID_INPUT, vm.error.value)

        assertTrue(stationCalls.isEmpty())
        assertEquals(2, vm.stations.value.size)
    }

    @Test
    fun `a hub failure on save is an error and the list stays as it was`() {
        hubWith(2)
        hub.failWith("setStations", hubFailure(400))

        vm.addStation("New", "dQw4w9WgXcQ", 0)

        assertEquals(FamilyError.INVALID_INPUT, vm.error.value)
        assertEquals((1..2).map { station(it) }, vm.stations.value)
        assertEquals(StationsState.SYNCED, vm.stationsState.value)
    }

    @Test
    fun `defaults are filled in with videos and saved once every station has one`() {
        vm.loadSettings()
        val ids = vm.stations.value.map { it.id }

        ids.dropLast(1).forEachIndexed { i, id ->
            vm.updateStation(id, vm.stations.value[i].title, video(i), 0)
            assertTrue("nothing is sent while a station still lacks its video", stationCalls.isEmpty())
            assertEquals(StationsState.DRAFT, vm.stationsState.value)
        }
        assertEquals(1, vm.stations.value.count { it.seedVideoId.isEmpty() })

        vm.updateStation(ids.last(), vm.stations.value.last().title, "dQw4w9WgXcQ", 0)

        assertEquals(1, stationCalls.size)
        assertEquals(ids, hub.stations.map { it.id })
        assertEquals(StationsState.SYNCED, vm.stationsState.value)
        assertEquals("Bollywood Classics", hub.stations.first().title)
    }

    @Test
    fun `unsaved edits to the defaults survive a refresh`() {
        vm.loadSettings()
        val first = vm.stations.value.first().id
        vm.updateStation(first, "Mummy's Favourites", video(1), 0)

        vm.loadSettings()

        assertEquals("Mummy's Favourites", vm.stations.value.first().title)
    }

    @Test
    fun `once a list has been saved an empty list from the hub is shown as empty, not defaulted again`() {
        hubWith(2)
        vm.removeStation("s2")
        assertTrue(store.stationsSaved)

        hub.stations = emptyList()
        vm.loadSettings()

        assertTrue(vm.stations.value.isEmpty())
        assertEquals(StationsState.SYNCED, vm.stationsState.value)
    }

    @Test
    fun `the saved marker outlives the view model`() {
        store.stationsSaved = true
        hub.stations = emptyList()

        vm.loadSettings()

        assertTrue(vm.stations.value.isEmpty())
    }

    @Test
    fun `station edits before the list has loaded are refused, never sent over the hub's list`() {
        vm.addStation("Early", "dQw4w9WgXcQ", 0)

        assertEquals(FamilyError.UNKNOWN, vm.error.value)
        assertTrue(stationCalls.isEmpty())
    }

    @Test
    fun `a failed station load reports the error and leaves the editor unloaded`() {
        hub.failWith("getStations", OFFLINE)

        vm.loadSettings()

        assertEquals(FamilyError.OFFLINE, vm.error.value)
        assertEquals(StationsState.NOT_LOADED, vm.stationsState.value)
        assertTrue(vm.stations.value.isEmpty())
    }

    @Test
    fun `a station edit without the admin key reports it and does not call the hub`() {
        hubWith(2)
        store.admin = null

        vm.removeStation("s1")

        assertEquals(FamilyError.NO_ADMIN_KEY, vm.error.value)
        assertTrue(stationCalls.isEmpty())
    }

    @Test
    fun `kill-switches start with every feature on`() {
        assertEquals(
            mapOf("sangat" to true, "parivaar_mix" to true, "family_playlists" to true, "cast_relay" to true),
            vm.killSwitches.value,
        )
    }

    @Test
    fun `loading shows what the hub says`() {
        hub.killSwitches = linkedMapOf("sangat" to false, "parivaar_mix" to true, "family_playlists" to true, "cast_relay" to false)

        vm.loadSettings()

        assertEquals(false, vm.killSwitches.value["sangat"])
        assertEquals(false, vm.killSwitches.value["cast_relay"])
        assertEquals(true, vm.killSwitches.value["parivaar_mix"])
    }

    @Test
    fun `toggling a kill-switch sends just that key and shows the hub's answer`() {
        vm.loadSettings()

        vm.toggleKillSwitch("parivaar_mix", false)

        assertEquals(listOf("setKillSwitches($TEST_ADMIN_KEY,parivaar_mix=false)"), hub.callsTo("setKillSwitches"))
        assertEquals(false, vm.killSwitches.value["parivaar_mix"])
        assertEquals(true, vm.killSwitches.value["sangat"])

        vm.toggleKillSwitch("parivaar_mix", true)

        assertEquals("setKillSwitches($TEST_ADMIN_KEY,parivaar_mix=true)", hub.callsTo("setKillSwitches").last())
        assertEquals(true, vm.killSwitches.value["parivaar_mix"])
    }

    @Test
    fun `a key that is not one of the four features is refused without asking the hub`() {
        vm.toggleKillSwitch("not_a_feature", false)

        assertEquals(FamilyError.INVALID_INPUT, vm.error.value)
        assertTrue(hub.callsTo("setKillSwitches").isEmpty())
    }

    @Test
    fun `a failed toggle is an error and the switch stays where it was`() {
        hub.failWith("setKillSwitches", OFFLINE)

        vm.toggleKillSwitch("sangat", false)

        assertEquals(FamilyError.OFFLINE, vm.error.value)
        assertEquals(true, vm.killSwitches.value["sangat"])
    }

    @Test
    fun `a failed kill-switch load leaves every feature on`() {
        hub.failWith("getKillSwitches", OFFLINE)

        vm.loadSettings()

        assertEquals(true, vm.killSwitches.value["sangat"])
        assertEquals(FamilyError.OFFLINE, vm.error.value)
    }

    @Test
    fun `the four features are exactly the hub's keys, on unless the hub says otherwise`() {
        assertEquals(
            listOf("sangat", "parivaar_mix", "family_playlists", "cast_relay"),
            FamilyFeature.entries.map { it.key },
        )
        assertTrue(FamilyFeature.entries.all { FamilyFeature.isEnabled(emptyMap(), it) })
        assertEquals(false, FamilyFeature.isEnabled(mapOf("sangat" to false), FamilyFeature.SANGAT))
        assertEquals(true, FamilyFeature.isEnabled(mapOf("sangat" to false), FamilyFeature.CAST_RELAY))
    }

    @Test
    fun `the icon set has six entries and any index maps onto one`() {
        assertEquals(6, StationIcons.COUNT)
        assertEquals(StationIcons.drawableFor(0), StationIcons.drawableFor(StationIcons.COUNT))
        assertEquals(StationIcons.drawableFor(0), StationIcons.drawableFor(-6))
    }

    // --- Task 9: the station cache Saral and the car read -------------------

    @Test
    fun `a list loaded from the hub refreshes the cache`() {
        hubWith(3)

        assertEquals(listOf((1..3).map { station(it) }), cache.replaced.toList())
    }

    @Test
    fun `a saved edit refreshes the cache with the list the hub returned`() {
        hubWith(2)
        cache.replaced.clear()

        vm.addStation("Ghazals", "dQw4w9WgXcQ", iconIndex = 3)

        assertEquals(listOf(hub.stations), cache.replaced.toList())
        assertEquals(3, cache.snapshot().size)
    }

    @Test
    fun `a removal and a reorder refresh the cache too`() {
        hubWith(3)
        cache.replaced.clear()

        vm.removeStation("s2")
        vm.moveStation("s3", -1)

        assertEquals(listOf(listOf("s1", "s3")), cache.replaced.take(1).map { list -> list.map { it.id } })
        assertEquals(listOf("s3", "s1"), cache.snapshot().map { it.id })
    }

    @Test
    fun `the unsaved defaults never reach the cache`() {
        vm.loadSettings()
        vm.updateStation(vm.stations.value.first().id, "Mummy's Favourites", video(1), 0)

        assertTrue(cache.replaced.isEmpty())
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun `a failed save leaves the cache alone`() {
        hubWith(2)
        cache.replaced.clear()
        hub.failWith("setStations", hubFailure(400))

        vm.addStation("New", "dQw4w9WgXcQ", 0)

        assertTrue(cache.replaced.isEmpty())
    }

    @Test
    fun `a cache that cannot be written does not turn a saved edit into an error`() {
        hubWith(2)
        cache.failWrites = java.io.IOException("disk full")

        vm.addStation("New", "dQw4w9WgXcQ", 0)

        assertNull(vm.error.value)
        assertEquals(StationsState.SYNCED, vm.stationsState.value)
        assertEquals(3, vm.stations.value.size)
    }
}

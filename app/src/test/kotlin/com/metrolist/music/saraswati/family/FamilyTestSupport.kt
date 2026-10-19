/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — in-memory stand-ins for the hub and for this phone's
// credential storage. FakeHub behaves like the Worker where that matters to
// the view models (a claim adds a pending roster row, approve/revoke/role
// change it) and can be told to fail any one call, so a test never needs
// the network. HubClient itself is covered against a real server in
// HubClientTest.
package com.metrolist.music.saraswati.family

import com.metrolist.music.saraswati.saral.GrandparentRoleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

const val TEST_HUB_URL = "https://hub.example.workers.dev"
const val TEST_TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
const val TEST_ADMIN_KEY = "test-admin-key"

fun hubFailure(status: Int): Throwable = HubException(status, "hub answered HTTP $status")

class FakeHub : HubApi {
    val calls = CopyOnWriteArrayList<String>()

    /** When each pairingStatus call arrived, so a test can measure the gaps between polls. */
    val statusCallNanos = CopyOnWriteArrayList<Long>()
    private val failures = HashMap<String, Throwable>()
    private val statusScript = java.util.concurrent.ConcurrentLinkedQueue<Result<PairingStatus>>()

    @Volatile var devices: List<RosterEntry> = emptyList()

    @Volatile var health: List<DeviceHealth> = emptyList()

    @Volatile var stations: List<Station> = emptyList()

    @Volatile var killSwitches: Map<String, Boolean> =
        linkedMapOf("sangat" to true, "parivaar_mix" to true, "family_playlists" to true, "cast_relay" to true)

    @Volatile var pairingToken = PairingToken(TEST_TOKEN, "2026-10-19T18:27:00.000Z")

    /** While set, getStations waits on it: stands in for a hub that is slow or unreachable. */
    @Volatile var stationsGate: CompletableDeferred<Unit>? = null

    @Volatile var meRole: DeviceRole = DeviceRole.ADULT

    /** Every later call to [function] fails with [failure] until [heal] is called. */
    fun failWith(
        function: String,
        failure: Throwable,
    ) {
        failures[function] = failure
    }

    fun heal(function: String) {
        failures.remove(function)
    }

    /** The next pairingStatus answers, in order: a [PairingStatus] succeeds, a [Throwable] fails. */
    fun scriptStatus(vararg steps: Any) {
        steps.forEach { statusScript.add(if (it is Throwable) Result.failure(it) else Result.success(it as PairingStatus)) }
    }

    fun callsTo(function: String): List<String> = calls.filter { it.substringBefore('(') == function }

    private fun <T> run(
        function: String,
        args: String,
        ok: () -> T,
    ): Result<T> {
        calls.add("$function($args)")
        failures[function]?.let { return Result.failure(it) }
        return Result.success(ok())
    }

    private fun update(
        deviceId: String,
        change: (RosterEntry) -> RosterEntry,
    ) {
        devices = devices.map { if (it.deviceId == deviceId) change(it) else it }
    }

    override suspend fun createPairing(adminKey: String) = run("createPairing", adminKey) { pairingToken }

    override suspend fun claimPairing(
        token: String,
        deviceName: String,
        role: DeviceRole?,
    ) = run("claimPairing", "$token,$deviceName,${role?.wireName}") {
        val id = "device-${devices.size + 1}"
        devices = devices + RosterEntry(id, deviceName, "pending", null, role ?: DeviceRole.ADULT)
        ClaimResult(id, "pending", "claim-secret-$id")
    }

    override suspend fun pairingStatus(
        deviceId: String,
        claimSecret: String,
    ): Result<PairingStatus> {
        calls.add("pairingStatus($deviceId,$claimSecret)")
        statusCallNanos.add(System.nanoTime())
        failures["pairingStatus"]?.let { return Result.failure(it) }
        return statusScript.poll() ?: Result.success(PairingStatus.Pending)
    }

    override suspend fun getRoster(adminKey: String) = run("getRoster", adminKey) { devices }

    override suspend fun approveDevice(
        adminKey: String,
        deviceId: String,
    ) = run("approveDevice", "$adminKey,$deviceId") {
        update(deviceId) { it.copy(status = "approved", approvedAt = 1_000L) }
        ApprovedDevice(deviceId, "session-$deviceId", "approved")
    }

    override suspend fun revokeDevice(
        adminKey: String,
        deviceId: String,
    ) = run("revokeDevice", "$adminKey,$deviceId") {
        update(deviceId) { it.copy(status = "revoked") }
    }

    override suspend fun setRole(
        adminKey: String,
        deviceId: String,
        role: DeviceRole,
    ) = run("setRole", "$adminKey,$deviceId,${role.wireName}") {
        update(deviceId) { it.copy(role = role) }
        RoleUpdate(deviceId, role)
    }

    override suspend fun getMe(sessionToken: String) = run("getMe", sessionToken) { Me("me", meRole) }

    override suspend fun getStations(auth: String): Result<List<Station>> {
        calls.add("getStations($auth)")
        stationsGate?.await()
        failures["getStations"]?.let { return Result.failure(it) }
        return Result.success(stations)
    }

    override suspend fun setStations(
        adminKey: String,
        stations: List<Station>,
    ) = run("setStations", "$adminKey,${stations.joinToString("|") { it.id }}") {
        this.stations = stations
        stations
    }

    override suspend fun getKillSwitches(auth: String) = run("getKillSwitches", auth) { killSwitches }

    override suspend fun setKillSwitches(
        adminKey: String,
        changes: Map<String, Boolean>,
    ) = run("setKillSwitches", "$adminKey,${changes.entries.joinToString("|") { "${it.key}=${it.value}" }}") {
        killSwitches = killSwitches + changes
        killSwitches
    }

    override suspend fun reportHealth(
        sessionToken: String,
        deviceId: String,
        versionCode: Int,
        versionName: String,
        timezone: String,
    ) = run("reportHealth", "$sessionToken,$deviceId") { }

    override suspend fun getHealth(adminKey: String) = run("getHealth", adminKey) { health }
}

class FakeStore(
    var admin: String? = null,
) : FamilyStore {
    var owner = false
    var session: FamilySession? = null
    var claim: PendingClaim? = null
    var stationsSaved = false
    var failWrites: Throwable? = null
    var failReads: Throwable? = null

    private fun checkWrite() {
        failWrites?.let { throw it }
    }

    override suspend fun adminKey() = admin

    override suspend fun saveOwner(adminKey: String) {
        checkWrite()
        admin = adminKey
        owner = true
    }

    override suspend fun session(): FamilySession? {
        failReads?.let { throw it }
        return session
    }

    override suspend fun pendingClaim() = claim

    override suspend fun savePendingClaim(claim: PendingClaim) {
        checkWrite()
        this.claim = claim
    }

    override suspend fun completeClaim(
        deviceId: String,
        sessionToken: String,
    ) {
        checkWrite()
        session = FamilySession(deviceId, sessionToken)
        claim = null
    }

    override suspend fun clearPendingClaim() {
        checkWrite()
        claim = null
    }

    override suspend fun stationsEverSaved() = stationsSaved

    override suspend fun markStationsSaved() {
        checkWrite()
        stationsSaved = true
    }
}

val OFFLINE: Throwable = IOException("no route to host")

/** Mirrors DataStoreStationsCache's contract: synchronous snapshot; an empty list never replaces a good one. */
class FakeStationsCache(
    initial: List<Station> = emptyList(),
) : StationsCache {
    private val state = MutableStateFlow(initial)
    override val stations: StateFlow<List<Station>> = state.asStateFlow()

    val replaced = CopyOnWriteArrayList<List<Station>>()
    var failWrites: Throwable? = null

    override suspend fun replace(stations: List<Station>) {
        failWrites?.let { throw it }
        replaced.add(stations)
        if (stations.isNotEmpty()) state.value = stations
    }
}

class FakeRoleStore(
    var grandparent: Boolean = false,
) : GrandparentRoleStore {
    var failWrites: Throwable? = null
    var failReads: Throwable? = null
    val writes = CopyOnWriteArrayList<Boolean>()

    override suspend fun isGrandparent(): Boolean {
        failReads?.let { throw it }
        return grandparent
    }

    override suspend fun setGrandparent(value: Boolean) {
        failWrites?.let { throw it }
        writes.add(value)
        grandparent = value
    }
}

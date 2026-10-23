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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.PriorityQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

const val TEST_HUB_URL = "https://hub.example.workers.dev"
const val TEST_TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
const val TEST_ADMIN_KEY = "test-admin-key"

fun hubFailure(status: Int): Throwable = HubException(status, "hub answered HTTP $status")

data class HealthCall(
    val sessionToken: String,
    val deviceId: String,
    val versionCode: Int,
    val versionName: String,
    val timezone: String,
)

private val ADMIN_FUNCTIONS =
    setOf("createPairing", "getRoster", "approveDevice", "revokeDevice", "setRole", "setStations", "setKillSwitches", "getHealth")

class FakeHub : HubApi {
    val calls = CopyOnWriteArrayList<String>()

    /** When each pairingStatus call arrived, so a test can measure the gaps between polls. */
    val statusCallNanos = CopyOnWriteArrayList<Long>()

    /** [clockMs] at each pairingStatus call: point it at a [VirtualTimeDispatcher] to read gaps in virtual time. */
    val statusCallMs = CopyOnWriteArrayList<Long>()

    @Volatile var clockMs: () -> Long = { System.currentTimeMillis() }
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

    /** While set, getKillSwitches waits on it: stands in for a refresh that is still in flight. */
    @Volatile var killSwitchesGate: CompletableDeferred<Unit>? = null

    /** While set, createPairing waits on it: a pairing request that is still in flight. */
    @Volatile var pairingGate: CompletableDeferred<Unit>? = null

    /** While set, claimPairing waits on it: a claim that is still in flight. */
    @Volatile var claimGate: CompletableDeferred<Unit>? = null

    /** The time the hub stamps on a claim made through [claimPairing]. */
    @Volatile var claimClockMs: Long = 1_000L

    @Volatile var meRole: DeviceRole = DeviceRole.ADULT

    /** false stands in for a /me answer with no role, or one this build does not know. */
    @Volatile var meRoleKnown: Boolean = true

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

    /**
     * While set, the hub behaves as one whose ADMIN_KEY is this value: every admin call made with another key
     * answers 401. Stands in for a key that was rotated on the hub after the phone stored the old one.
     */
    @Volatile var onlyAdminKey: String? = null

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
        onlyAdminKey?.let { accepted ->
            if (function in ADMIN_FUNCTIONS && args.substringBefore(',') != accepted) return Result.failure(hubFailure(401))
        }
        return Result.success(ok())
    }

    private fun update(
        deviceId: String,
        change: (RosterEntry) -> RosterEntry,
    ) {
        devices = devices.map { if (it.deviceId == deviceId) change(it) else it }
    }

    override suspend fun createPairing(adminKey: String): Result<PairingToken> {
        pairingGate?.await()
        return run("createPairing", adminKey) { pairingToken }
    }

    override suspend fun claimPairing(
        token: String,
        deviceName: String,
        role: DeviceRole?,
    ): Result<ClaimResult> {
        calls.add("claimPairing($token,$deviceName,${role?.wireName})")
        claimGate?.await()
        failures["claimPairing"]?.let { return Result.failure(it) }
        val id = "device-${devices.size + 1}"
        devices = devices + RosterEntry(id, deviceName, "pending", null, role ?: DeviceRole.ADULT, claimedAt = claimClockMs)
        return Result.success(ClaimResult(id, "pending", "claim-secret-$id"))
    }

    override suspend fun pairingStatus(
        deviceId: String,
        claimSecret: String,
    ): Result<PairingStatus> {
        calls.add("pairingStatus($deviceId,$claimSecret)")
        statusCallNanos.add(System.nanoTime())
        statusCallMs.add(clockMs())
        failures["pairingStatus"]?.let { return Result.failure(it) }
        return statusScript.poll() ?: Result.success(PairingStatus.Pending)
    }

    override suspend fun getRoster(adminKey: String) = run("getRoster", adminKey) { devices }

    override suspend fun approveDevice(
        adminKey: String,
        deviceId: String,
    ) = run("approveDevice", "$adminKey,$deviceId") {
        update(deviceId) { it.copy(status = "approved", approvedAt = 1_000L) }
        // The hardened hub never returns the claiming phone's session token to the owner.
        ApprovedDevice(deviceId, "approved")
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

    override suspend fun getMe(sessionToken: String) = run("getMe", sessionToken) { Me("me", meRole, meRoleKnown) }

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

    override suspend fun getKillSwitches(auth: String): Result<Map<String, Boolean>> {
        calls.add("getKillSwitches($auth)")
        killSwitchesGate?.await()
        failures["getKillSwitches"]?.let { return Result.failure(it) }
        return Result.success(killSwitches)
    }

    override suspend fun setKillSwitches(
        adminKey: String,
        changes: Map<String, Boolean>,
    ) = run("setKillSwitches", "$adminKey,${changes.entries.joinToString("|") { "${it.key}=${it.value}" }}") {
        killSwitches = killSwitches + changes
        killSwitches
    }

    /** What each reportHealth call carried, in full: [calls] holds only the token and device id. */
    val healthReports = CopyOnWriteArrayList<HealthCall>()

    override suspend fun reportHealth(
        sessionToken: String,
        deviceId: String,
        versionCode: Int,
        versionName: String,
        timezone: String,
    ) = run("reportHealth", "$sessionToken,$deviceId") {
        healthReports.add(HealthCall(sessionToken, deviceId, versionCode, versionName, timezone))
        Unit
    }

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

    /** A session whose token sits in storage but cannot be opened now (Keystore unavailable): session() reads null. */
    var sessionSlotUnreadable = false
    var strikes = UnauthorizedStrikes.NONE
    var failProbe: Throwable? = null

    /** While set, pendingClaim() waits on it: storage that is slow to answer. */
    @Volatile var pendingClaimGate: CompletableDeferred<Unit>? = null

    /** Runs at the start of unauthorizedStrikes(): lets a test hold a verdict in the middle of reading the record. */
    @Volatile var onReadStrikes: (suspend () -> Unit)? = null

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

    // Mirrors DataStoreFamilyStore: the plain device-id slot, never opened through the Keystore.
    override suspend fun sessionDeviceId(): String? = session?.deviceId

    override suspend fun hasStoredSession(): Boolean {
        failProbe?.let { throw it }
        return session != null || sessionSlotUnreadable
    }

    override suspend fun pendingClaim(): PendingClaim? {
        failReads?.let { throw it }
        pendingClaimGate?.await()
        return claim
    }

    // Mirrors DataStoreFamilyStore: a stored session is never replaced by a new claim.
    override suspend fun savePendingClaim(claim: PendingClaim) {
        checkWrite()
        check(session == null) { "a session is stored; a new claim must not replace it" }
        this.claim = claim
    }

    val cleared = CopyOnWriteArrayList<String>()

    override suspend fun clearSession(deviceId: String) {
        checkWrite()
        cleared.add(deviceId)
        if (session?.deviceId == deviceId) {
            session = null
            strikes = UnauthorizedStrikes.NONE
        }
    }

    override suspend fun unauthorizedStrikes(): UnauthorizedStrikes {
        failReads?.let { throw it }
        onReadStrikes?.invoke()
        return strikes
    }

    override suspend fun saveUnauthorizedStrikes(strikes: UnauthorizedStrikes) {
        checkWrite()
        this.strikes = strikes
    }

    override suspend fun resetUnauthorizedStrikes() {
        checkWrite()
        strikes = UnauthorizedStrikes.NONE
    }

    override suspend fun completeClaim(
        deviceId: String,
        sessionToken: String,
    ) {
        checkWrite()
        session = FamilySession(deviceId, sessionToken)
        claim = null
        strikes = UnauthorizedStrikes.NONE
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

/**
 * Mirrors DataStoreStationsCache's contract: synchronous snapshot; an empty list never replaces a good one;
 * a station whose seed is not a valid video id is dropped;
 * [loaded] is false until storage has been read ([finishLoading]) or a list has been saved.
 */
class FakeStationsCache(
    initial: List<Station> = emptyList(),
    loadedAtStart: Boolean = true,
) : StationsCache {
    private val state = MutableStateFlow(initial)
    override val stations: StateFlow<List<Station>> = state.asStateFlow()

    private val loadedState = MutableStateFlow(loadedAtStart)
    override val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

    /** Storage finished reading: [stored] (possibly nothing) is the list, and the cache is loaded. */
    fun finishLoading(stored: List<Station> = emptyList()) {
        state.value = stored
        loadedState.value = true
    }

    val replaced = CopyOnWriteArrayList<List<Station>>()
    var failWrites: Throwable? = null

    override suspend fun replace(stations: List<Station>) {
        failWrites?.let { throw it }
        replaced.add(stations)
        val kept = stations.filter { StationVideo.isVideoId(it.seedVideoId) }
        if (kept.isNotEmpty()) {
            state.value = kept
            loadedState.value = true
        }
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

/** A throttle that never says no: for tests of what a refresh does, not how often it may run. */
fun noThrottle() = ResumeThrottle(0L) { 0L }

/**
 * A manual clock for [ResumeThrottle]: moves only when a test says so, so a throttle test never sleeps.
 */
class TestClock(
    var nowMs: Long = 0L,
) {
    fun advance(ms: Long) {
        nowMs += ms
    }

    val reading: () -> Long = { nowMs }
}

/**
 * A single-threaded dispatcher on virtual time, so a test of "nothing is polled for ten minutes" runs in
 * microseconds and cannot flake. kotlinx-coroutines-test is not a dependency of this project (and a new
 * dependency means a new entry in the verification metadata), so this is the small part of it these tests
 * need: [delay] waits on [currentTime], and [advanceBy] runs everything that falls due.
 *
 * // SARASWATI: the scope that runs on this dispatcher is pure virtual time, but a real hub/datastore call
 * // made from it can suspend through a genuinely different, real-threaded dispatcher (e.g. Dispatchers.IO)
 * // and then resume back here from that real thread. That resumption calls [dispatch] concurrently with
 * // whatever the test thread is doing to [queue] inside [advanceBy]/[runCurrent] at that moment.
 * // java.util.PriorityQueue is not thread-safe under concurrent mutation; unsynchronized access here
 * // corrupted it (observed locally as a NullPointerException out of PriorityQueue's internal comparator,
 * // and — the shape seen flaking on CI — a Task surviving a failed queue.remove() to run a second time,
 * // double-resuming its continuation as kotlinx.coroutines' "Already resumed"). Every access to the queue
 * // and the clock is now behind [lock]; task execution itself stays outside it so a task that re-enters
 * // this dispatcher (e.g. by dispatching more work) cannot hold the lock across arbitrary continuation code.
 */
@OptIn(InternalCoroutinesApi::class)
class VirtualTimeDispatcher :
    CoroutineDispatcher(),
    Delay {
    private class Task(
        val time: Long,
        val order: Long,
        val block: Runnable,
    )

    private val lock = Any()
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.time }, { it.order }))
    private var counter = 0L

    @Volatile var currentTime = 0L
        private set

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        synchronized(lock) { queue.add(Task(currentTime, counter++, block)) }
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val task =
            synchronized(lock) {
                Task(currentTime + timeMillis.coerceAtLeast(0), counter++) { continuation.resume(Unit) }
                    .also { queue.add(it) }
            }
        continuation.invokeOnCancellation { synchronized(lock) { queue.remove(task) } }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        val task =
            synchronized(lock) {
                Task(currentTime + timeMillis.coerceAtLeast(0), counter++, block).also { queue.add(it) }
            }
        return DisposableHandle { synchronized(lock) { queue.remove(task) } }
    }

    /** Runs what is due now (and what that makes due), without moving the clock. */
    fun runCurrent() = advanceBy(0)

    /** Moves the clock forward by [ms], running every task that falls due on the way, in time order. */
    fun advanceBy(ms: Long) {
        val target = synchronized(lock) { currentTime + ms }
        while (true) {
            // SARASWATI: the "nothing left due" check and the currentTime = target jump that ends this
            // call must happen in the SAME critical section. Split across two, a real thread's dispatch()
            // landing in the gap between them would be stamped with the stale pre-jump currentTime instead
            // of target, sit in the queue un-run until the next advanceBy/runCurrent, and be delayed by one
            // step in a way that depends on real IO timing rather than virtual time.
            val next =
                synchronized(lock) {
                    val candidate = queue.peek()?.takeIf { it.time <= target }
                    if (candidate == null) {
                        currentTime = target
                        return@synchronized null
                    }
                    queue.remove(candidate)
                    currentTime = maxOf(currentTime, candidate.time)
                    candidate
                } ?: break
            next.block.run()
        }
    }
}

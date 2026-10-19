/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 6 — the networking layer for the family hub Worker
// (worker/src/FamilyHub.ts). Nothing here touches UI or storage.
//
// Contract for every function: it builds its own request with the bearer it
// is given (or none, for the two pre-credential pairing calls) and returns a
// Result; it never throws. A non-2xx answer is a HubException carrying the
// HTTP status; a transport problem is the IOException itself; a 2xx whose
// body is unusable is a HubException too. CancellationException is the one
// thing deliberately let through, so a cancelled caller stays cancelled.
//
// Security properties, each pinned in HubClientTest:
//  - Redirects are never followed. A 3xx on this API is a bug or an attack,
//    and following it would re-send the bearer to wherever it points.
//  - No failure carries a bearer, a request body or a response body, and no
//    failure chains a parser exception (kotlinx.serialization quotes the
//    offending input in its messages, and that input can be a session token).
//  - Device ids go into URL path segments only after a strict allowlist, so
//    a hostile roster entry cannot rewrite the path ("..", "/", "?").
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HubClient(
    private val baseUrl: String,
    client: OkHttpClient,
) : HubApi {
    // Shares the caller's connection pool, dispatcher and timeouts; only the
    // redirect policy is pinned here.
    private val http: OkHttpClient =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    override suspend fun createPairing(adminKey: String): Result<PairingToken> =
        send("POST", listOf("pairing", "create"), adminKey, "{}") {
            json.decodeFromString<PairingToken>(it)
        }

    /**
     * First contact from a phone that scanned a pairing link: it has no
     * credential yet, so no bearer. [role] is what this phone is being set up
     * as; null sends nothing and the Worker defaults to adult. A role the
     * Worker rejects is a 400 that does not spend the pairing token.
     */
    override suspend fun claimPairing(
        token: String,
        deviceName: String,
        role: DeviceRole?,
    ): Result<ClaimResult> =
        send("POST", listOf("pairing", "claim"), null, json.encodeToString(ClaimRequest(token, deviceName, role))) {
            json.decodeFromString<ClaimResult>(it)
        }

    /**
     * How a claimed-but-unapproved phone learns it was approved and collects
     * its own session token. 404 means the claim is gone (unknown, revoked or
     * already collected), not "still waiting".
     */
    override suspend fun pairingStatus(
        deviceId: String,
        claimSecret: String,
    ): Result<PairingStatus> =
        send("POST", listOf("pairing", "status"), null, json.encodeToString(PairingStatusRequest(deviceId, claimSecret))) {
            val wire = json.decodeFromString<PairingStatusResponse>(it)
            when (wire.status) {
                "pending" -> PairingStatus.Pending
                "approved" ->
                    PairingStatus.Approved(
                        wire.sessionToken?.takeIf { token -> token.isNotEmpty() }
                            ?: throw SerializationException("approved without a session token"),
                    )
                else -> throw SerializationException("unknown pairing status")
            }
        }

    override suspend fun getRoster(adminKey: String): Result<List<RosterEntry>> =
        send("GET", listOf("roster"), adminKey, null) {
            json.decodeFromString<RosterResponse>(it).devices
        }

    override suspend fun approveDevice(
        adminKey: String,
        deviceId: String,
    ): Result<ApprovedDevice> {
        if (!isSafeSegment(deviceId)) return invalidDeviceId()
        return send("POST", listOf("roster", deviceId, "approve"), adminKey, "{}") {
            // The hub sends no session token any more. One that still arrives is dropped here: the owner's
            // phone must never hold the claiming phone's credential.
            json.decodeFromString<ApprovedDevice>(it).copy(sessionToken = null)
        }
    }

    override suspend fun revokeDevice(
        adminKey: String,
        deviceId: String,
    ): Result<Unit> {
        if (!isSafeSegment(deviceId)) return invalidDeviceId()
        return send("POST", listOf("roster", deviceId, "revoke"), adminKey, "{}") { }
    }

    /** The owner flips a phone's role (how a grandparent-mode phone is switched back). 404 is unknown or revoked. */
    override suspend fun setRole(
        adminKey: String,
        deviceId: String,
        role: DeviceRole,
    ): Result<RoleUpdate> {
        if (!isSafeSegment(deviceId)) return invalidDeviceId()
        return send("PUT", listOf("roster", deviceId, "role"), adminKey, json.encodeToString(RoleRequest(role))) {
            json.decodeFromString<RoleUpdate>(it)
        }
    }

    /** A paired phone asking what the owner has set it up as. Device bearer only; the admin key gets a 401. */
    override suspend fun getMe(sessionToken: String): Result<Me> =
        send("GET", listOf("me"), sessionToken, null) {
            json.decodeFromString<MeResponse>(it).toMe()
        }

    /** [auth] is the owner's admin key or a paired phone's session token; the Worker accepts either. */
    override suspend fun getStations(auth: String): Result<List<Station>> =
        send("GET", listOf("stations"), auth, null) {
            json.decodeFromString<StationsEnvelope>(it).stations
        }

    override suspend fun setStations(
        adminKey: String,
        stations: List<Station>,
    ): Result<List<Station>> =
        send("PUT", listOf("stations"), adminKey, json.encodeToString(StationsEnvelope(stations))) {
            json.decodeFromString<StationsEnvelope>(it).stations
        }

    /** [auth] is the owner's admin key or a paired phone's session token; the Worker accepts either. */
    override suspend fun getKillSwitches(auth: String): Result<Map<String, Boolean>> =
        send("GET", listOf("killswitches"), auth, null) {
            parseKillSwitches(it)
        }

    override suspend fun setKillSwitches(
        adminKey: String,
        changes: Map<String, Boolean>,
    ): Result<Map<String, Boolean>> =
        send("PUT", listOf("killswitches"), adminKey, json.encodeToString(KILL_SWITCHES, changes)) {
            parseKillSwitches(it)
        }

    /**
     * A paired phone's own heartbeat. The Worker requires [deviceId] in the
     * body and rejects it unless it matches the device behind [sessionToken].
     * [timezone] is this phone's own, so the owner sees each device's local
     * time as the device reports it.
     */
    override suspend fun reportHealth(
        sessionToken: String,
        deviceId: String,
        versionCode: Int,
        versionName: String,
        timezone: String,
    ): Result<Unit> =
        send(
            "POST",
            listOf("health"),
            sessionToken,
            json.encodeToString(HealthReportRequest(deviceId, versionCode, versionName, timezone)),
        ) { }

    override suspend fun getHealth(adminKey: String): Result<List<DeviceHealth>> =
        send("GET", listOf("health"), adminKey, null) {
            json.decodeFromString<HealthResponse>(it).devices
        }

    // SARASWATI: Phase 4 Task 8 — the Sangat, Parivaar Mix and family-playlist routes. They are on HubClient
    // alone, not on HubApi: only the Phase 4 packages call them, and the Phase 3 view models and their
    // fakes stay as they were. Same contract as above: a Result, never a throw.

    /** Rooms whose host is connected. [sessionToken] is a paired phone's (the Worker also takes the admin key). 503 means Sangat is off. */
    suspend fun getLiveSangat(sessionToken: String): Result<List<LiveSangatRoom>> =
        send("GET", listOf("sangat", "live"), sessionToken, null) {
            json.decodeFromString<LiveSangatEnvelope>(it).rooms
        }

    /** The host's phone saying it is driving. 404 is an unknown room, 403 a caller that is not the room's host device. */
    suspend fun setSangatCarMode(
        sessionToken: String,
        roomCode: String,
        carMode: Boolean,
    ): Result<Unit> =
        send("PUT", listOf("sangat", "car"), sessionToken, json.encodeToString(CarModeRequest(roomCode, carMode))) { }

    /** Sets this phone's own Parivaar Mix row; the Worker takes the device from the bearer, never from the body. */
    suspend fun putParivaarPlaylist(
        sessionToken: String,
        playlistId: String,
        memberName: String,
    ): Result<ParivaarPlaylist> =
        send("PUT", listOf("parivaar", "playlist"), sessionToken, json.encodeToString(ParivaarPlaylistRequest(playlistId, memberName))) {
            json.decodeFromString<ParivaarPlaylist>(it)
        }

    /** In the Worker's order (binary collation of the name); the caller sorts for display. */
    suspend fun getParivaarPlaylists(sessionToken: String): Result<List<ParivaarPlaylist>> =
        send("GET", listOf("parivaar", "playlists"), sessionToken, null) {
            json.decodeFromString<ParivaarEnvelope>(it).playlists
        }

    suspend fun getFamilyPlaylists(sessionToken: String): Result<List<FamilyPlaylist>> =
        send("GET", listOf("family-playlists"), sessionToken, null) {
            json.decodeFromString<FamilyPlaylistsEnvelope>(it).playlists
        }

    /** 409 is a playlist the family already has, or the twentieth being past the limit of 20. */
    suspend fun addFamilyPlaylist(
        sessionToken: String,
        playlistId: String,
        title: String,
    ): Result<FamilyPlaylist> =
        send("POST", listOf("family-playlists"), sessionToken, json.encodeToString(FamilyPlaylistRequest(playlistId, title))) {
            json.decodeFromString<FamilyPlaylist>(it)
        }

    /** [auth] is the owner's admin key, or the session token of the phone that added it; any other phone gets a 403. */
    suspend fun removeFamilyPlaylist(
        auth: String,
        id: String,
    ): Result<Unit> {
        if (!isSafeSegment(id)) return Result.failure(IllegalArgumentException("invalid playlist id"))
        return send("DELETE", listOf("family-playlists", id), auth, null) { }
    }

    /**
     * Where the Sangat WebSocket lives: the hub's base URL with https:// turned into wss://, plus /ws. A base that
     * is not https (or not set, as in the foss flavor) gives "", never a ws:// address, so nothing can be sent in the clear.
     */
    fun sangatWebSocketUrl(): String {
        val base = baseUrl.trim().toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return ""
        val https =
            base
                .newBuilder()
                .query(null)
                .fragment(null)
                .addPathSegment("ws")
                .build()
                .toString()
        return "wss://" + https.removePrefix("https://")
    }

    private suspend fun <T> send(
        method: String,
        path: List<String>,
        bearer: String?,
        body: String?,
        parse: (String) -> T,
    ): Result<T> {
        val base =
            baseUrl.trim().toHttpUrlOrNull()
                ?: return Result.failure(IllegalStateException("hub base URL is not configured"))

        val request =
            try {
                Request
                    .Builder()
                    .url(
                        base
                            .newBuilder()
                            .query(null)
                            .fragment(null)
                            .apply { path.forEach { addPathSegment(it) } }
                            .build(),
                    ).method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                    .build()
            } catch (e: IllegalArgumentException) {
                // OkHttp rejects e.g. a bearer containing a newline. Not chained: the message may quote it.
                return Result.failure(IllegalArgumentException("invalid hub request"))
            }

        val raw =
            try {
                http.newCall(request).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            }

        if (raw.code !in 200..299) {
            return Result.failure(HubException(raw.code, "hub answered HTTP ${raw.code}"))
        }
        return try {
            Result.success(parse(raw.body))
        } catch (e: Exception) {
            Result.failure(HubException(raw.code, "hub answered HTTP ${raw.code} with an unusable body"))
        }
    }

    /**
     * The flat `{key: boolean}` map. Keys this build does not know are returned for the caller to ignore,
     * and an entry that is not a boolean is skipped, so a hub that grows a switch (or sends an odd value
     * for one) never takes the known switches down with it. Anything but an object is unusable.
     */
    private fun parseKillSwitches(body: String): Map<String, Boolean> {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: throw SerializationException("kill-switches is not an object")
        return obj.entries
            .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { key to it } }
            .toMap()
    }

    private fun isSafeSegment(segment: String): Boolean = SAFE_SEGMENT.matches(segment)

    private fun <T> invalidDeviceId(): Result<T> = Result.failure(IllegalArgumentException("invalid device id"))

    private class RawResponse(
        val code: Int,
        val body: String,
    )

    /**
     * Runs the call on OkHttp's dispatcher and reads the (bounded) body there,
     * not on the caller's thread, so a caller on Main never does blocking I/O.
     */
    private suspend fun Call.await(): RawResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        try {
                            continuation.resume(response.use { RawResponse(it.code, if (it.isSuccessful) it.readBounded() else "") })
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    }
                },
            )
        }

    private fun Response.readBounded(): String {
        val peeked = peekBody(MAX_RESPONSE_BYTES + 1)
        if (peeked.contentLength() > MAX_RESPONSE_BYTES) throw IOException("hub response is too large")
        return peeked.string()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val KILL_SWITCHES = MapSerializer(String.serializer(), Boolean.serializer())

        // The Worker mints device ids with crypto.randomUUID(); this admits
        // that and nothing that could change a URL's structure.
        val SAFE_SEGMENT = Regex("[A-Za-z0-9_-]{1,128}")

        // The biggest legitimate answer is a roster or health list, a few KB.
        const val MAX_RESPONSE_BYTES = 1L shl 20
    }
}

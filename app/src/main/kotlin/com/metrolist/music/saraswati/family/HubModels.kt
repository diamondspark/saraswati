/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 6 — the family hub Worker's JSON shapes (worker/src/FamilyHub.ts).
// Field names are the Worker's wire names. Timestamps are not normalised:
// `expiresAt` of a pairing token is the ISO-8601 string the Worker sends,
// while `approvedAt`, `claimedAt` and `lastSeenAt` are epoch milliseconds
// (null until the Worker has a value for them).
//
// Fix wave B1: every response model ignores fields it does not know (HubClient's
// Json), so a hub that has grown a field never breaks an older app; the same goes
// for a kill-switch key (see HubClient.parseKillSwitches). The approve answer is
// `{deviceId, status}` with no session token: that credential belongs to the
// claiming phone, and ApprovedDevice never holds one.
//
// Every type that carries a credential (a pairing token, a claim secret or a
// session token) overrides toString() so a stray log line or crash report
// never prints it: PairingToken, ClaimResult, PairingStatus.Approved,
// ApprovedDevice and the wire requests ClaimRequest and PairingStatusRequest.
package com.metrolist.music.saraswati.family

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the owner has set a phone up as. The Worker owns the value; the phone
 * caches it and refreshes it from GET /me. A wire value this build does not
 * know reads as [ADULT], so an older app never invents a locked-down mode it
 * cannot render.
 */
@Serializable(with = DeviceRoleSerializer::class)
enum class DeviceRole(
    val wireName: String,
) {
    ADULT("adult"),
    GRANDPARENT("grandparent"),
    ;

    companion object {
        fun fromWire(value: String?): DeviceRole = entries.firstOrNull { it.wireName == value } ?: ADULT
    }
}

internal object DeviceRoleSerializer : KSerializer<DeviceRole> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DeviceRole", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: DeviceRole,
    ) = encoder.encodeString(value.wireName)

    override fun deserialize(decoder: Decoder): DeviceRole = DeviceRole.fromWire(decoder.decodeString())
}

@Serializable
data class PairingToken(
    val token: String,
    val expiresAt: String,
) {
    override fun toString(): String = "PairingToken(token=<redacted>, expiresAt=$expiresAt)"
}

@Serializable
data class ClaimResult(
    val deviceId: String,
    val status: String,
    val claimSecret: String,
) {
    override fun toString(): String = "ClaimResult(deviceId=$deviceId, status=$status, claimSecret=<redacted>)"
}

/** The answer to "has the owner approved my claim yet?" (POST /pairing/status). */
sealed interface PairingStatus {
    data object Pending : PairingStatus

    data class Approved(
        val sessionToken: String,
    ) : PairingStatus {
        override fun toString(): String = "Approved(sessionToken=<redacted>)"
    }
}

@Serializable
data class RosterEntry(
    val deviceId: String,
    val deviceName: String? = null,
    val status: String,
    val approvedAt: Long? = null,
    val role: DeviceRole = DeviceRole.ADULT,
    // When the phone made its request (epoch ms). Null when the hub left it out or sent something
    // that is not a number: one odd field never fails the whole roster.
    @Serializable(with = LenientEpochMillisSerializer::class) val claimedAt: Long? = null,
)

/** An epoch-millisecond timestamp that reads as null for anything but a non-negative number. */
internal object LenientEpochMillisSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientEpochMillis", PrimitiveKind.LONG)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(
        encoder: Encoder,
        value: Long?,
    ) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeLong()
        if (element is JsonNull || element !is JsonPrimitive || element.isString) return null
        val whole = element.content.toLongOrNull()
        val millis = whole ?: element.content.toDoubleOrNull()?.takeIf { it.isFinite() && it < MAX_EXACT_DOUBLE }?.toLong()
        return millis?.takeIf { it >= 0 }
    }

    // A double holds integers exactly up to 2^53; past it the value is no timestamp.
    private const val MAX_EXACT_DOUBLE = 9.007199254740992E15
}

/**
 * GET /me: who the Worker says this phone is, and what the owner set it up as. [role] reads a
 * missing or unknown wire value as adult, like every other role in these models. [roleKnown] is
 * false for exactly that case, so a caller that must not act on a guess (the Saral cache) can
 * tell "the hub said adult" from "the hub said nothing I understand".
 */
data class Me(
    val deviceId: String,
    val role: DeviceRole = DeviceRole.ADULT,
    val roleKnown: Boolean = true,
)

/** PUT /roster/{deviceId}/role: the role now stored for that device. */
@Serializable
data class RoleUpdate(
    val deviceId: String,
    val role: DeviceRole = DeviceRole.ADULT,
)

/**
 * POST /roster/{id}/approve: the hub answers `{deviceId, status}`. [sessionToken] is optional only
 * so that an answer from a hub that still sends one reads; HubClient drops it, and nothing in the
 * app stores or shows it.
 */
@Serializable
data class ApprovedDevice(
    val deviceId: String,
    val status: String,
    val sessionToken: String? = null,
) {
    override fun toString(): String =
        "ApprovedDevice(deviceId=$deviceId, status=$status, sessionToken=${if (sessionToken == null) "null" else "<redacted>"})"
}

@Serializable
data class Station(
    val id: String,
    val title: String,
    val seedVideoId: String,
    val iconIndex: Int,
)

/** One device's last self-reported health row. [timezone] is the device's own, never inferred. */
@Serializable
data class DeviceHealth(
    val deviceId: String,
    val deviceName: String? = null,
    val versionCode: Int,
    val versionName: String,
    val timezone: String,
    val lastSeenAt: Long,
)

/**
 * SARASWATI: Phase 4 Task 8 — a Sangat room whose host is connected right now (GET /sangat/live, and the
 * answer to PUT /sangat/car). [startedAt] is the ISO-8601 string the Worker sends. The room code is a join
 * handle, not a credential: the hub still refuses a phone that is not approved.
 */
@Serializable
data class LiveSangatRoom(
    val roomCode: String,
    val hostName: String,
    val hostDeviceId: String,
    val carMode: Boolean,
    val memberCount: Int,
    val startedAt: String,
    // SARASWATI: final fix wave (M2) — display only: the song on now. Absent from an older hub, null before the first song.
    val nowPlaying: LiveSangatSong? = null,
)

/** What the live-room list shows of the song on now: a title and an artist, never an id or a link. */
@Serializable
data class LiveSangatSong(
    val title: String,
    val artist: String = "",
)

/** One phone's published Parivaar Mix playlist (PUT /parivaar/playlist, GET /parivaar/playlists). */
@Serializable
data class ParivaarPlaylist(
    val deviceId: String,
    val memberName: String,
    val playlistId: String,
    val updatedAt: String,
)

/** One of the family's shared YouTube playlists (GET and POST /family-playlists). */
@Serializable
data class FamilyPlaylist(
    val id: String,
    val playlistId: String,
    val title: String,
    val addedByDeviceId: String,
    val addedAt: String,
)

/**
 * A non-2xx answer from the hub (or a 2xx whose body was unusable, in which
 * case [status] is that 2xx code). [status] is what callers branch on: 401
 * bad or revoked credential, 404 unknown or already used, 410 expired, 429
 * rate limited. Deliberately carries neither the request's bearer nor the
 * response body.
 */
class HubException(
    val status: Int,
    message: String,
) : Exception(message)

// Wire-only envelopes: what the Worker wraps its lists in, and the request
// bodies it expects. Never exposed to callers.

@Serializable
internal data class RosterResponse(
    val devices: List<RosterEntry>,
)

@Serializable
internal data class MeResponse(
    val deviceId: String,
    // A string, not DeviceRole, so a role that is absent, null or not one of ours stays visible.
    val role: String? = null,
) {
    fun toMe(): Me {
        val known = DeviceRole.entries.firstOrNull { it.wireName == role }
        return Me(deviceId, known ?: DeviceRole.ADULT, roleKnown = known != null)
    }
}

@Serializable
internal data class HealthResponse(
    val devices: List<DeviceHealth>,
)

@Serializable
internal data class StationsEnvelope(
    val stations: List<Station>,
)

@Serializable
internal data class PairingStatusResponse(
    val status: String,
    val sessionToken: String? = null,
) {
    // SARASWATI: fix wave A (M6) — the session token is a credential; the default toString would print it.
    override fun toString(): String = "PairingStatusResponse(status=$status, sessionToken=${if (sessionToken == null) "null" else "<redacted>"})"
}

@Serializable
internal data class ClaimRequest(
    val token: String,
    val deviceName: String,
    // Null is omitted from the body (kotlinx default), so the Worker applies its own default.
    val role: DeviceRole? = null,
) {
    override fun toString(): String = "ClaimRequest(token=<redacted>, deviceName=$deviceName, role=$role)"
}

@Serializable
internal data class RoleRequest(
    val role: DeviceRole,
)

@Serializable
internal data class PairingStatusRequest(
    val deviceId: String,
    val claimSecret: String,
) {
    override fun toString(): String = "PairingStatusRequest(deviceId=$deviceId, claimSecret=<redacted>)"
}

@Serializable
internal data class LiveSangatEnvelope(
    val rooms: List<LiveSangatRoom>,
)

@Serializable
internal data class ParivaarEnvelope(
    val playlists: List<ParivaarPlaylist>,
)

@Serializable
internal data class FamilyPlaylistsEnvelope(
    val playlists: List<FamilyPlaylist>,
)

@Serializable
internal data class CarModeRequest(
    val roomCode: String,
    val carMode: Boolean,
)

@Serializable
internal data class ParivaarPlaylistRequest(
    val playlistId: String,
    val memberName: String,
)

@Serializable
internal data class FamilyPlaylistRequest(
    val playlistId: String,
    val title: String,
)

@Serializable
internal data class HealthReportRequest(
    val deviceId: String,
    val versionCode: Int,
    val versionName: String,
    val timezone: String,
)

// SARASWATI: Sangat push — PUT /push/token. The FCM token is a delivery address for this phone only.
@Serializable
internal data class PushTokenRequest(
    val token: String,
)

// SARASWATI: Phase 6 Task 10 — POST /ask. The question is the person's own words; the hub gets nothing else
// about them. The reply's intent is read as a raw object and checked by ask/AskModels.kt.
@Serializable
internal data class AskWireRequest(
    val question: String,
    val locale: String,
) {
    override fun toString(): String = "AskWireRequest(question=<redacted>, locale=$locale)"
}

@Serializable
internal data class AskWireReply(
    val intent: JsonObject? = null,
    val fallback: Boolean = false,
)

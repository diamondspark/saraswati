/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 6 — the family hub Worker's JSON shapes (worker/src/FamilyHub.ts).
// Field names are the Worker's wire names. Timestamps are not normalised:
// `expiresAt` of a pairing token is the ISO-8601 string the Worker sends,
// while `approvedAt` and `lastSeenAt` are epoch milliseconds (null until the
// Worker has a value for them).
//
// Every type that carries a credential (a pairing token, a claim secret or a
// session token) overrides toString() so a stray log line or crash report
// never prints it: PairingToken, ClaimResult, PairingStatus.Approved,
// ApprovedDevice and the wire requests ClaimRequest and PairingStatusRequest.
package com.metrolist.music.saraswati.family

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
)

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

@Serializable
data class ApprovedDevice(
    val deviceId: String,
    val sessionToken: String,
    val status: String,
) {
    override fun toString(): String = "ApprovedDevice(deviceId=$deviceId, status=$status, sessionToken=<redacted>)"
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
)

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
internal data class HealthReportRequest(
    val deviceId: String,
    val versionCode: Int,
    val versionName: String,
    val timezone: String,
)

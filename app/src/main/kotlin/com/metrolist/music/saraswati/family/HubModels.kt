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
// The three types that carry a bearer credential override toString() so a
// stray log line or crash report never prints it.
package com.metrolist.music.saraswati.family

import kotlinx.serialization.Serializable

@Serializable
data class PairingToken(
    val token: String,
    val expiresAt: String,
)

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

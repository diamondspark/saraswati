/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the hub calls the view models depend on, as an
// interface, so they run against a fake in plain JUnit. HubClient is the
// only implementation (it is a final class, as Task 6 specified); the
// contract each function keeps is documented on HubClient.
package com.metrolist.music.saraswati.family

import java.util.TimeZone

/** Where this build's hub lives. Empty (foss and izzy flavors) means "no hub". */
data class HubConfig(
    val baseUrl: String,
)

interface HubApi {
    suspend fun createPairing(adminKey: String): Result<PairingToken>

    suspend fun claimPairing(
        token: String,
        deviceName: String,
        role: DeviceRole? = null,
    ): Result<ClaimResult>

    suspend fun pairingStatus(
        deviceId: String,
        claimSecret: String,
    ): Result<PairingStatus>

    suspend fun getRoster(adminKey: String): Result<List<RosterEntry>>

    suspend fun approveDevice(
        adminKey: String,
        deviceId: String,
    ): Result<ApprovedDevice>

    suspend fun revokeDevice(
        adminKey: String,
        deviceId: String,
    ): Result<Unit>

    suspend fun setRole(
        adminKey: String,
        deviceId: String,
        role: DeviceRole,
    ): Result<RoleUpdate>

    suspend fun getMe(sessionToken: String): Result<Me>

    suspend fun getStations(auth: String): Result<List<Station>>

    suspend fun setStations(
        adminKey: String,
        stations: List<Station>,
    ): Result<List<Station>>

    suspend fun getKillSwitches(auth: String): Result<Map<String, Boolean>>

    suspend fun setKillSwitches(
        adminKey: String,
        changes: Map<String, Boolean>,
    ): Result<Map<String, Boolean>>

    suspend fun reportHealth(
        sessionToken: String,
        deviceId: String,
        versionCode: Int,
        versionName: String,
        timezone: String = TimeZone.getDefault().id,
    ): Result<Unit>

    suspend fun getHealth(adminKey: String): Result<List<DeviceHealth>>
}

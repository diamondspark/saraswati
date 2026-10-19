/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — what this phone remembers about the family hub: the
// owner's admin key, or the claim/session of a paired phone. An interface so
// the view models run against memory in JUnit; DataStoreFamilyStore is the
// real one.
//
// Every secret goes through SealedPreferences (Keystore-sealed, one purpose
// per key). A plain `prefs[key] = value` would be wiped by SecretScrubber on
// the next start, so none is written here. The device id is not a secret and
// is stored plain.
package com.metrolist.music.saraswati.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.FamilyAdminKeyKey
import com.metrolist.music.constants.FamilyDeviceIdKey
import com.metrolist.music.constants.FamilyHubClaimSecretKey
import com.metrolist.music.constants.FamilyHubSessionTokenKey
import com.metrolist.music.constants.FamilyStationsSavedKey
import com.metrolist.music.constants.IsFamilyOwnerKey
import com.metrolist.music.saraswati.security.CookieVault
import com.metrolist.music.saraswati.security.SealedPreferences
import kotlinx.coroutines.flow.first

/** A paired phone's hub identity. [toString] never prints the token. */
data class FamilySession(
    val deviceId: String,
    val sessionToken: String,
) {
    override fun toString(): String = "FamilySession(deviceId=$deviceId, sessionToken=<redacted>)"
}

/** A claim made, not yet approved. [toString] never prints the secret. */
data class PendingClaim(
    val deviceId: String,
    val claimSecret: String,
) {
    override fun toString(): String = "PendingClaim(deviceId=$deviceId, claimSecret=<redacted>)"
}

interface FamilyStore {
    suspend fun adminKey(): String?

    /** Keeps [adminKey] sealed and marks this phone as the owner's. */
    suspend fun saveOwner(adminKey: String)

    suspend fun session(): FamilySession?

    suspend fun pendingClaim(): PendingClaim?

    suspend fun savePendingClaim(claim: PendingClaim)

    /** The owner approved: keep the session, drop the claim secret. */
    suspend fun completeClaim(
        deviceId: String,
        sessionToken: String,
    )

    suspend fun clearPendingClaim()

    /** True once the owner has saved a station list to the hub from this phone. */
    suspend fun stationsEverSaved(): Boolean

    suspend fun markStationsSaved()
}

class DataStoreFamilyStore(
    private val dataStore: DataStore<Preferences>,
    private val vault: CookieVault,
) : FamilyStore {
    private suspend fun prefs() = dataStore.data.first()

    private fun Preferences.sealed(key: Preferences.Key<String>): String? = SealedPreferences.unseal(key, this[key], vault)

    override suspend fun adminKey(): String? = prefs().sealed(FamilyAdminKeyKey)

    override suspend fun saveOwner(adminKey: String) {
        dataStore.edit {
            SealedPreferences.write(it, FamilyAdminKeyKey, adminKey, vault)
            it[IsFamilyOwnerKey] = true
        }
    }

    override suspend fun session(): FamilySession? {
        val prefs = prefs()
        val deviceId = prefs[FamilyDeviceIdKey] ?: return null
        val token = prefs.sealed(FamilyHubSessionTokenKey) ?: return null
        return FamilySession(deviceId, token)
    }

    override suspend fun pendingClaim(): PendingClaim? {
        val prefs = prefs()
        if (prefs.sealed(FamilyHubSessionTokenKey) != null) return null
        val deviceId = prefs[FamilyDeviceIdKey] ?: return null
        val secret = prefs.sealed(FamilyHubClaimSecretKey) ?: return null
        return PendingClaim(deviceId, secret)
    }

    override suspend fun savePendingClaim(claim: PendingClaim) {
        dataStore.edit {
            it.remove(FamilyHubSessionTokenKey)
            it[FamilyDeviceIdKey] = claim.deviceId
            SealedPreferences.write(it, FamilyHubClaimSecretKey, claim.claimSecret, vault)
        }
    }

    override suspend fun completeClaim(
        deviceId: String,
        sessionToken: String,
    ) {
        dataStore.edit {
            it[FamilyDeviceIdKey] = deviceId
            SealedPreferences.write(it, FamilyHubSessionTokenKey, sessionToken, vault)
            it.remove(FamilyHubClaimSecretKey)
        }
    }

    override suspend fun clearPendingClaim() {
        dataStore.edit {
            it.remove(FamilyHubClaimSecretKey)
            if (it[FamilyHubSessionTokenKey] == null) it.remove(FamilyDeviceIdKey)
        }
    }

    override suspend fun stationsEverSaved(): Boolean = prefs()[FamilyStationsSavedKey] == true

    override suspend fun markStationsSaved() {
        dataStore.edit { it[FamilyStationsSavedKey] = true }
    }
}

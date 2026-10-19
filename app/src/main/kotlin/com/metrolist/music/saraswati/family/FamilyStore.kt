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
import androidx.datastore.preferences.core.MutablePreferences
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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

/**
 * SARASWATI: fix wave A (I1, I4) — every read, unseal, seal and write runs on
 * [io], so the Keystore is never touched on the caller's thread (Main, for the
 * view models). A value the Keystore cannot open right now reads as absent but
 * is never removed here: nothing in this class deletes a credential because
 * it failed to open. Secrets are sealed before the DataStore edit, on [io]
 * explicitly, rather than inside the edit's transform, which runs wherever the
 * DataStore's own scope puts it.
 */
class DataStoreFamilyStore(
    private val dataStore: DataStore<Preferences>,
    private val vault: CookieVault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FamilyStore {
    private suspend fun prefs() = dataStore.data.first()

    private fun Preferences.sealed(key: Preferences.Key<String>): String? = SealedPreferences.unseal(key, this[key], vault)

    // An empty secret means "no credential" (as in SealedPreferences.write), so it is removed, not sealed.
    private fun sealedOrNull(
        key: Preferences.Key<String>,
        value: String,
    ): String? = if (value.isEmpty()) null else SealedPreferences.seal(key, value, vault)

    private fun MutablePreferences.put(
        key: Preferences.Key<String>,
        sealed: String?,
    ) {
        if (sealed == null) remove(key) else this[key] = sealed
    }

    override suspend fun adminKey(): String? = withContext(io) { prefs().sealed(FamilyAdminKeyKey) }

    override suspend fun saveOwner(adminKey: String) =
        withContext(io) {
            val sealed = sealedOrNull(FamilyAdminKeyKey, adminKey)
            dataStore.edit {
                it.put(FamilyAdminKeyKey, sealed)
                it[IsFamilyOwnerKey] = true
            }
            Unit
        }

    override suspend fun session(): FamilySession? =
        withContext(io) {
            val prefs = prefs()
            val deviceId = prefs[FamilyDeviceIdKey] ?: return@withContext null
            val token = prefs.sealed(FamilyHubSessionTokenKey) ?: return@withContext null
            FamilySession(deviceId, token)
        }

    override suspend fun pendingClaim(): PendingClaim? =
        withContext(io) {
            val prefs = prefs()
            if (prefs.sealed(FamilyHubSessionTokenKey) != null) return@withContext null
            val deviceId = prefs[FamilyDeviceIdKey] ?: return@withContext null
            val secret = prefs.sealed(FamilyHubClaimSecretKey) ?: return@withContext null
            PendingClaim(deviceId, secret)
        }

    override suspend fun savePendingClaim(claim: PendingClaim) =
        withContext(io) {
            val sealed = sealedOrNull(FamilyHubClaimSecretKey, claim.claimSecret)
            dataStore.edit {
                it.remove(FamilyHubSessionTokenKey)
                it[FamilyDeviceIdKey] = claim.deviceId
                it.put(FamilyHubClaimSecretKey, sealed)
            }
            Unit
        }

    override suspend fun completeClaim(
        deviceId: String,
        sessionToken: String,
    ) = withContext(io) {
        val sealed = sealedOrNull(FamilyHubSessionTokenKey, sessionToken)
        dataStore.edit {
            it[FamilyDeviceIdKey] = deviceId
            it.put(FamilyHubSessionTokenKey, sealed)
            it.remove(FamilyHubClaimSecretKey)
        }
        Unit
    }

    override suspend fun clearPendingClaim() =
        withContext(io) {
            dataStore.edit {
                it.remove(FamilyHubClaimSecretKey)
                if (it[FamilyHubSessionTokenKey] == null) it.remove(FamilyDeviceIdKey)
            }
            Unit
        }

    override suspend fun stationsEverSaved(): Boolean = withContext(io) { prefs()[FamilyStationsSavedKey] == true }

    override suspend fun markStationsSaved() =
        withContext(io) {
            dataStore.edit { it[FamilyStationsSavedKey] = true }
            Unit
        }
}

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
import com.metrolist.music.constants.FamilyUnauthorizedAtKey
import com.metrolist.music.constants.FamilyUnauthorizedCountKey
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

/**
 * SARASWATI: fix wave B2 — how many 401s in a row the hub has given this phone's session ([count]) and the
 * wall-clock time of the last one that was counted ([lastCountedAtMs], epoch ms; 0 while [count] is 0).
 */
data class UnauthorizedStrikes(
    val count: Int,
    val lastCountedAtMs: Long,
) {
    companion object {
        val NONE = UnauthorizedStrikes(0, 0L)
    }
}

interface FamilyStore {
    suspend fun adminKey(): String?

    /** Keeps [adminKey] sealed and marks this phone as the owner's. */
    suspend fun saveOwner(adminKey: String)

    suspend fun session(): FamilySession?

    /**
     * True if a session token is stored, judged on the stored slot alone: nothing is decrypted, so a
     * Keystore that is unavailable right now (which makes [session] read as absent) cannot hide a live
     * session. Join asks this before it spends a one-time pairing link.
     */
    suspend fun hasStoredSession(): Boolean

    /**
     * The device id of the stored session, read without opening the sealed token, so asking it often (the Sangat
     * poll does, every 30 seconds) costs no Keystore operation. Null without a stored session.
     */
    suspend fun sessionDeviceId(): String? = session()?.deviceId

    suspend fun pendingClaim(): PendingClaim?

    /**
     * Keeps a new claim. Never replaces a stored session: throws [IllegalStateException] if there is
     * one (a revoked phone first has its session cleared, see [clearSession]).
     */
    suspend fun savePendingClaim(claim: PendingClaim)

    /**
     * The hub refused [deviceId]'s session (it was revoked): forgets the session and the device id so
     * Join works again. The admin key and the Saral role cache are not touched, and a newer pairing
     * (a different device id) is left alone. The strikes of the cleared session go with it.
     */
    suspend fun clearSession(deviceId: String)

    /** The running count of 401s against the stored session (plain storage; see [UnauthorizedStrikes]). */
    suspend fun unauthorizedStrikes(): UnauthorizedStrikes

    suspend fun saveUnauthorizedStrikes(strikes: UnauthorizedStrikes)

    /** Back to no strikes: any answer from the hub that was not a 401 for the session. */
    suspend fun resetUnauthorizedStrikes()

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

    override suspend fun sessionDeviceId(): String? =
        withContext(io) {
            val prefs = prefs()
            prefs[FamilyDeviceIdKey]?.takeIf { prefs[FamilyHubSessionTokenKey] != null }
        }

    // SARASWATI: fix wave B2 — the raw slot, never unsealed: it answers even when the Keystore does not.
    override suspend fun hasStoredSession(): Boolean = withContext(io) { prefs()[FamilyHubSessionTokenKey] != null }

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
                // SARASWATI: fix wave A (M3) — a new claim used to remove the stored session here, so a
                // phone that was still paired lost its session to a second Join. The raw slot is checked,
                // not an unsealed value: a Keystore that is unavailable must not make a live session look absent.
                check(it[FamilyHubSessionTokenKey] == null) { "a session is stored; a new claim must not replace it" }
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
            // A new session starts with a clean record.
            it.remove(FamilyUnauthorizedCountKey)
            it.remove(FamilyUnauthorizedAtKey)
        }
        Unit
    }

    override suspend fun clearSession(deviceId: String) =
        withContext(io) {
            dataStore.edit {
                if (it[FamilyDeviceIdKey] == deviceId) {
                    it.remove(FamilyHubSessionTokenKey)
                    it.remove(FamilyHubClaimSecretKey)
                    it.remove(FamilyDeviceIdKey)
                    it.remove(FamilyUnauthorizedCountKey)
                    it.remove(FamilyUnauthorizedAtKey)
                }
            }
            Unit
        }

    override suspend fun unauthorizedStrikes(): UnauthorizedStrikes =
        withContext(io) {
            val prefs = prefs()
            val count = prefs[FamilyUnauthorizedCountKey] ?: 0
            if (count <= 0) UnauthorizedStrikes.NONE else UnauthorizedStrikes(count, prefs[FamilyUnauthorizedAtKey] ?: 0L)
        }

    override suspend fun saveUnauthorizedStrikes(strikes: UnauthorizedStrikes) =
        withContext(io) {
            dataStore.edit {
                it[FamilyUnauthorizedCountKey] = strikes.count
                it[FamilyUnauthorizedAtKey] = strikes.lastCountedAtMs
            }
            Unit
        }

    override suspend fun resetUnauthorizedStrikes() =
        withContext(io) {
            dataStore.edit {
                it.remove(FamilyUnauthorizedCountKey)
                it.remove(FamilyUnauthorizedAtKey)
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

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 8 — who this phone is to the hub, as the Phase 4 packages (the Sangat watcher,
// car mode, Parivaar Mix, family playlists) see it: a session token and a device id, or null for "not
// paired yet". An interface so those packages run against a plain fake in JUnit; the real one reads
// FamilyStore, so the token stays Keystore-sealed and is read fresh on each call, never held in a field.
package com.metrolist.music.saraswati.family

import kotlin.coroutines.cancellation.CancellationException

interface HubIdentity {
    /** The device-bearer session token, or null when this phone has none (or the Keystore cannot open it right now). */
    suspend fun sessionToken(): String?

    suspend fun deviceId(): String?
}

class FamilyStoreHubIdentity(
    private val store: FamilyStore,
) : HubIdentity {
    override suspend fun sessionToken(): String? = readSession()?.sessionToken

    override suspend fun deviceId(): String? = readSession()?.deviceId

    private suspend fun readSession(): FamilySession? =
        try {
            store.session()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}

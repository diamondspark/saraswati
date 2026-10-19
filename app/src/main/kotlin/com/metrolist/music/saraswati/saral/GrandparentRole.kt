/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — Saral on or off, as a cache of the role the hub holds
// for this phone.
//
// The hub is the only authority. [GrandparentRoleSync.refresh] asks it (GET
// /me with this phone's session) on every launch and resume, and the answer
// is the only thing that ever changes the cached flag: grandparent turns Saral
// on, adult turns it off. Everything else leaves the flag exactly as it was,
// because the failure that matters is a grandparent suddenly dropped into the
// full app with Settings: no signal, a hub error, a revoked token, a phone
// that is unpaired, still pending approval or the owner's own (no session at
// all), a storage error, or an answer with no role this build understands.
// Nothing on the phone offers a way out; the owner flips the role in Manage
// Family and the next refresh picks it up.
package com.metrolist.music.saraswati.saral

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.GrandparentRoleKey
import com.metrolist.music.saraswati.family.DeviceRole
import com.metrolist.music.saraswati.family.FamilyStore
import com.metrolist.music.saraswati.family.HubApi
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

interface GrandparentRoleStore {
    suspend fun isGrandparent(): Boolean

    suspend fun setGrandparent(value: Boolean)
}

class DataStoreGrandparentRoleStore(
    private val dataStore: DataStore<Preferences>,
) : GrandparentRoleStore {
    override suspend fun isGrandparent(): Boolean = dataStore.data.first()[GrandparentRoleKey] == true

    override suspend fun setGrandparent(value: Boolean) {
        dataStore.edit { it[GrandparentRoleKey] = value }
    }
}

@Singleton
class GrandparentRoleSync
    @Inject
    constructor(
        private val hub: HubApi,
        private val family: FamilyStore,
        private val roles: GrandparentRoleStore,
    ) {
        /**
         * Asks the hub what this phone is and caches it. Returns the hub's answer,
         * or null when there was no answer (and so no change).
         */
        suspend fun refresh(): DeviceRole? {
            val session = guarded { family.session() } ?: return null
            val me = hub.getMe(session.sessionToken).getOrNull() ?: return null
            // A role the hub left out, or one this build does not know, reads as adult elsewhere;
            // here it must be no answer at all, or it would switch Saral off.
            if (!me.roleKnown) return null
            val role = me.role
            val grandparent = role == DeviceRole.GRANDPARENT
            guarded {
                if (roles.isGrandparent() != grandparent) roles.setGrandparent(grandparent)
            }
            return role
        }

        private suspend fun <T> guarded(block: suspend () -> T): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
    }

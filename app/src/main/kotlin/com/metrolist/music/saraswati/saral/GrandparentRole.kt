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
//
// Fix wave B1: MainActivity calls refresh on every resume, so a call inside the
// minute after the last hub attempt is skipped (a skip is "no answer", which
// changes nothing). Only a call that is about to ask the hub uses up the minute:
// an unpaired, pending or owner phone has nothing to ask with and does not.
// Join's own call after an approval passes force = true.
//
// Two exceptions to "nothing changes the flag but the hub's answer" (fix wave A):
// a phone that holds the admin key is the owner's and is never Saral (it is
// released if a stale flag has it), and a 401 clears the stored session but not
// the flag. Fix wave B2: not the first 401 but the third counted one (SessionVerdict).
package com.metrolist.music.saraswati.saral

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.GrandparentRoleKey
import com.metrolist.music.saraswati.family.DeviceRole
import com.metrolist.music.saraswati.family.FamilyStore
import com.metrolist.music.saraswati.family.HubApi
import com.metrolist.music.saraswati.family.ResumeThrottle
import com.metrolist.music.saraswati.family.judgeSessionAnswer
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
    internal constructor(
        private val hub: HubApi,
        private val family: FamilyStore,
        private val roles: GrandparentRoleStore,
        private val throttle: ResumeThrottle,
        private val wallClockMs: () -> Long = System::currentTimeMillis,
    ) {
        @Inject
        constructor(hub: HubApi, family: FamilyStore, roles: GrandparentRoleStore) :
            this(hub, family, roles, ResumeThrottle(ResumeThrottle.RESUME_INTERVAL_MS, SystemClock::elapsedRealtime))

        /**
         * Asks the hub what this phone is and caches it. Returns the hub's answer,
         * or null when there was no answer (and so no change). A call within a minute of the last
         * hub attempt is skipped unless [force] (a caller that must have the answer now).
         */
        suspend fun refresh(force: Boolean = false): DeviceRole? {
            // SARASWATI: fix wave A (M2) — the owner's own phone holds the admin key, and only the full
            // app can enter or use it, so this phone must never be locked into Saral: it is released if a
            // stale flag has it, and the hub is not asked what it is.
            if (guarded { family.adminKey() } != null) {
                guarded { if (roles.isGrandparent()) roles.setGrandparent(false) }
                return null
            }
            val session = guarded { family.session() } ?: return null
            if (!force && !throttle.tryAcquire()) return null
            val answer = hub.getMe(session.sessionToken)
            // SARASWATI: fix wave A (M3) — a revoked phone forgets its session and device id so Join works
            // again. The role cache stays as it is (a revoked grandparent phone stays in Saral rather than
            // being dropped into the full app). Fix wave B2: the hub's 401 is generic, so this takes
            // three counted 401s, and a success resets the count (SessionVerdict).
            judgeSessionAnswer(family, session, answer, wallClockMs)
            val me = answer.getOrNull() ?: return null
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

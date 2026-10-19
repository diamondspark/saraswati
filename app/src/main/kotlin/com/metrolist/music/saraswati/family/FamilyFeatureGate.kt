/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 8 — the owner's four kill-switches as the rest of the app reads them.
//
// Fail open: a feature is on until the hub has said otherwise, and a hub that cannot be reached, a phone
// with no session yet, or an answer that cannot be read never turns anything off. The other half: once the
// hub has said "off" the value is kept on the phone (a set of the keys that are off), so the owner's
// deliberate "off" survives a dead zone and a restart.
//
// [isEnabled] never suspends. The stored values are read once, shortly after the process starts; until then
// (and if storage cannot be read) every feature reads as on. A refresh that finishes first is not undone by
// that read. Keys the hub sends that this build does not know are dropped, and so are stored ones.
package com.metrolist.music.saraswati.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.FamilyFeaturesDisabledKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class FamilyFeatureGate(
    private val hub: HubApi,
    private val identity: HubIdentity,
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow(allOn())

    /** Every [FamilyFeature], true unless the hub's last answer said false. */
    val enabled: StateFlow<Map<FamilyFeature, Boolean>> = state.asStateFlow()

    // Set by the first of: storage finishing its read, or a refresh being applied.
    private val lock = Any()
    private var settled = false
    private val refreshLock = Mutex()

    internal val hydration: Job =
        scope.launch {
            val disabled = readStored()
            synchronized(lock) {
                if (!settled) {
                    settled = true
                    state.value = FamilyFeature.entries.associateWith { it.key !in disabled }
                }
            }
        }

    fun isEnabled(feature: FamilyFeature): Boolean = state.value[feature] ?: true

    /** Returns once the stored switches have been read (or a refresh has been applied); a connect at a cold start waits for it. */
    suspend fun awaitHydrated() = hydration.join()

    /**
     * Asks the hub for the switches with this phone's session token and keeps the answer. Does nothing without
     * a session, and keeps the last values on any failure. Never throws except to pass on a cancellation.
     */
    suspend fun refresh() {
        refreshLock.withLock {
            val token = identity.sessionToken() ?: return
            val fetched = hub.getKillSwitches(token).getOrNull() ?: return
            val next = FamilyFeature.entries.associateWith { FamilyFeature.isEnabled(fetched, it) }
            synchronized(lock) {
                settled = true
                state.value = next
            }
            try {
                dataStore.edit { prefs ->
                    prefs[FamilyFeaturesDisabledKey] = next.filterValues { !it }.keys.mapTo(HashSet()) { it.key }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Still applied for this run; the next answer writes it again.
            }
        }
    }

    private suspend fun readStored(): Set<String> =
        try {
            dataStore.data.first()[FamilyFeaturesDisabledKey].orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

    private companion object {
        fun allOn(): Map<FamilyFeature, Boolean> = FamilyFeature.entries.associateWith { true }
    }
}

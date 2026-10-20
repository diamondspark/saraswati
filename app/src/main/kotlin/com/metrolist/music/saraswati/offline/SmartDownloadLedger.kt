/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 10 — what "Raat ke Gaane" has downloaded on this phone, kept in DataStore: which songs it
// finished and when, which it asked for and has not finished, how many each night. Only songs listed here are
// ever stopped, resumed or removed by the smart downloads; a song the user downloaded is not in it.
// Damaged storage reads as an empty ledger, never a crash.
package com.metrolist.music.saraswati.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.SmartDownloadLedgerKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant

class SmartDownloadLedger(
    private val dataStore: DataStore<Preferences>,
) {
    /** The ledger now, then after every change. */
    val snapshots: Flow<LedgerSnapshot> = dataStore.data.map { decode(it[SmartDownloadLedgerKey]) }

    suspend fun snapshot(): LedgerSnapshot = decode(dataStore.data.first()[SmartDownloadLedgerKey])

    /** The songs asked for and neither finished nor failed. */
    suspend fun pendingIds(): Set<String> =
        snapshot().entries.filterValues { it.pendingSince != null && !it.isCompleted }.keys

    suspend fun markPending(
        id: String,
        now: Instant,
    ) = change { it.withEntry(id) { entry -> entry.copy(pendingSince = now.toEpochMilli()) } }

    suspend fun markDone(
        id: String,
        now: Instant,
        night: String,
    ) = change {
        val rolled = it.rolledTo(night)
        rolled
            .withEntry(id) { entry -> entry.copy(downloadedAt = now.toEpochMilli(), pendingSince = null, failures = 0) }
            .copy(doneTonight = rolled.doneTonight + 1, failuresInARow = 0)
    }

    suspend fun markFailed(
        id: String,
        night: String,
    ) = change {
        val rolled = it.rolledTo(night)
        rolled
            .withEntry(id) { entry -> entry.copy(pendingSince = null, failures = entry.failures + 1) }
            .copy(failuresInARow = rolled.failuresInARow + 1)
    }

    /** Starts [night] if the ledger is still on an earlier one, keeping last night's count. */
    suspend fun rollTo(night: String) = change { it.rolledTo(night) }

    /** A station's radio could not be asked. It counts as a failure of [night], like a song that did not download. */
    suspend fun markFetchFailed(night: String) =
        change {
            val rolled = it.rolledTo(night)
            rolled.copy(failuresInARow = rolled.failuresInARow + 1)
        }

    suspend fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        change { it.copy(entries = it.entries - ids.toSet()) }
    }

    private suspend fun change(edit: (LedgerSnapshot) -> LedgerSnapshot) {
        dataStore.edit { prefs ->
            val next = edit(decode(prefs[SmartDownloadLedgerKey]))
            prefs[SmartDownloadLedgerKey] = json.encodeToString(LedgerSnapshot.serializer(), next)
        }
    }

    private fun LedgerSnapshot.withEntry(
        id: String,
        update: (LedgerEntry) -> LedgerEntry,
    ): LedgerSnapshot = copy(entries = entries + (id to update(entries[id] ?: LedgerEntry())))

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): LedgerSnapshot =
            if (raw == null) {
                LedgerSnapshot.EMPTY
            } else {
                try {
                    json.decodeFromString(LedgerSnapshot.serializer(), raw)
                } catch (e: Exception) {
                    LedgerSnapshot.EMPTY
                }
            }
    }
}

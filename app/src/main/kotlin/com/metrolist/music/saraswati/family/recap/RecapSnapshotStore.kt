/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 14 — the family's top-songs lists as this phone saw them, kept for the recap. Eight days of
// snapshots (about nine with daily refreshes), plus the newest one older than that, so the week to compare against is held.
// Local songs are dropped before anything is written. Takes no network port: the lists come from the Parivaar refresh.
//
// Storage that cannot be read reads as no snapshots; a failed write keeps the snapshots in memory for this run.
package com.metrolist.music.saraswati.family.recap

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.saraswati.family.parivaar.MemberTopSongs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class RecapSnapshot(
    val takenAt: Long,
    val members: List<MemberTopSongs>,
)

class RecapSnapshotStore(
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) {
    private val held = MutableStateFlow<List<RecapSnapshot>>(emptyList())
    private val lock = Mutex()
    private var loaded = false

    /** Newest first. Changes when a snapshot is recorded or the stored ones are read. */
    val snapshots: StateFlow<List<RecapSnapshot>> = held.asStateFlow()

    fun current(): RecapSnapshot? = held.value.firstOrNull()

    fun previous(): RecapSnapshot? = previousOf(held.value)

    /** Reads the stored snapshots once; a record that runs first reads them itself. */
    suspend fun ensureLoaded() {
        lock.withLock { loadLocked() }
    }

    suspend fun record(members: List<MemberTopSongs>) {
        lock.withLock {
            loadLocked()
            val snapshot =
                RecapSnapshot(
                    takenAt = clock.millis(),
                    members = members.map { member -> member.copy(tracks = member.tracks.filterNot { it.videoId.startsWith(LOCAL_PREFIX) }) },
                )
            val kept = retain(held.value + snapshot)
            write(kept)
            held.value = kept
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        held.value = read()
    }

    private suspend fun read(): List<RecapSnapshot> =
        try {
            val raw = dataStore.data.first()[SnapshotsKey]
            if (raw == null) emptyList() else retain(json.decodeFromString(LIST, raw))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    private suspend fun write(snapshots: List<RecapSnapshot>) {
        try {
            dataStore.edit { it[SnapshotsKey] = json.encodeToString(LIST, snapshots) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Still held for this run; the next record writes them again.
        }
    }

    companion object {
        const val RECAP_PERIOD_DAYS = 7L
        const val RETAIN_DAYS = 8L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val LOCAL_PREFIX = "local:"
        /** Stored snapshots. Public so BackupPolicy keeps other members' top songs out of backups. */
        val SnapshotsKey = stringPreferencesKey("familyRecapSnapshots")
        private val LIST = ListSerializer(RecapSnapshot.serializer())
        private val json = Json { ignoreUnknownKeys = true }

        /** The newest snapshot at least [RECAP_PERIOD_DAYS] older than the first (newest) one, or null. */
        fun previousOf(newestFirst: List<RecapSnapshot>): RecapSnapshot? {
            val now = newestFirst.firstOrNull() ?: return null
            val cutoff = now.takenAt - RECAP_PERIOD_DAYS * DAY_MS
            return newestFirst.drop(1).firstOrNull { it.takenAt <= cutoff }
        }

        /**
         * Newest first: every snapshot within [RETAIN_DAYS] of the newest, then the newest one older than that. Daily
         * refreshes therefore keep the one at least [RECAP_PERIOD_DAYS] old that "New this week" compares against.
         */
        internal fun retain(all: List<RecapSnapshot>): List<RecapSnapshot> {
            val newestFirst = all.sortedByDescending { it.takenAt }
            val newest = newestFirst.firstOrNull() ?: return emptyList()
            val windowStart = newest.takenAt - RETAIN_DAYS * DAY_MS
            val inWindow = newestFirst.filter { it.takenAt >= windowStart }
            val older = newestFirst.firstOrNull { it.takenAt < windowStart }
            return inWindow + listOfNotNull(older)
        }
    }
}

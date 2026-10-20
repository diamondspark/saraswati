/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 9 — the songs saved for each family Station so it can play with no network: the
// station's seed, the tracks chosen for it and when they were chosen. Kept on the phone; [cached] is a plain
// function that returns at once with whatever is held (possibly nothing while storage is still being read), so
// the car and Saral never wait on it. Damaged storage reads as nothing, never a crash.
//
// Writers (Task 11, nightly) call [save]; [retain] drops what the family no longer has: a removed station, or a
// station whose seed was changed (its saved songs belong to the old seed).
package com.metrolist.music.saraswati.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.StationOfflineSetsKey
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class StationOfflineSet(
    val stationId: String,
    val seedVideoId: String,
    val tracks: List<MixTrack>,
    val refreshedAt: Long,
    /** When the radio was last asked for this station, answered or not. Zero before the first ask. */
    val lastAttemptAt: Long = 0L,
)

class StationOfflineStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val writeLock = Mutex()
    private val lock = Any()

    @Volatile private var held: Map<String, StationOfflineSet> = emptyMap()
    private var settled = false

    internal val hydration: Job =
        scope.launch {
            val stored =
                try {
                    decode(dataStore.data.first()[StationOfflineSetsKey])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyMap()
                }
            synchronized(lock) {
                if (!settled) {
                    settled = true
                    held = stored
                }
            }
        }

    /** The sets held right now, by station id. Never suspends and never touches storage or the network. */
    fun cached(): Map<String, StationOfflineSet> = held

    /** The set saved for the station whose seed is [seedVideoId], if any. */
    fun forSeed(seedVideoId: String): StationOfflineSet? = held.values.firstOrNull { it.seedVideoId == seedVideoId }

    suspend fun save(set: StationOfflineSet) = change { it + (set.stationId to set.copy(tracks = set.tracks.take(MAX_TRACKS))) }

    /** Records that the radio was asked for [station] at [at], keeping any tracks already held for it. */
    suspend fun markAttempted(
        station: Station,
        at: Long,
    ) = change { sets ->
        val held = sets[station.id]?.takeIf { it.seedVideoId == station.seedVideoId }
        val base = held ?: StationOfflineSet(station.id, station.seedVideoId, emptyList(), refreshedAt = 0L)
        sets + (station.id to base.copy(lastAttemptAt = at))
    }

    suspend fun retain(stations: List<Station>) =
        change { sets ->
            sets.filterValues { set -> stations.any { it.id == set.stationId && it.seedVideoId == set.seedVideoId } }
        }

    private suspend fun change(edit: (Map<String, StationOfflineSet>) -> Map<String, StationOfflineSet>) {
        hydration.join()
        writeLock.withLock {
            val next = edit(held)
            if (next == held) return
            dataStore.edit { it[StationOfflineSetsKey] = json.encodeToString(SERIALIZER, next) }
            // After the write, so a failed write leaves cached() where storage is.
            synchronized(lock) {
                settled = true
                held = next
            }
        }
    }

    companion object {
        const val MAX_TRACKS = 100
        private val json = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = MapSerializer(String.serializer(), StationOfflineSet.serializer())

        private fun decode(raw: String?): Map<String, StationOfflineSet> =
            if (raw == null) {
                emptyMap()
            } else {
                try {
                    json.decodeFromString(SERIALIZER, raw).mapValues { (_, set) -> set.copy(tracks = set.tracks.take(MAX_TRACKS)) }
                } catch (e: Exception) {
                    emptyMap()
                }
            }
    }
}

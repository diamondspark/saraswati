/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 11 — the last Parivaar Mix this phone built, kept on the phone so the car's folder
// and Saral can show it without waiting for the hub or YouTube. [cached] is a plain function that returns at
// once with whatever is held, possibly the empty mix while storage is still being read.
//
// Damaged storage reads as the empty mix, never a crash. A mix saved while storage is still being read is
// not undone by the older stored one. Both directions keep at most MIX_MAX songs.
package com.metrolist.music.saraswati.family.parivaar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.ParivaarMixKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class ParivaarMix(
    val builtAt: Long,
    val tracks: List<MixTrack>,
) {
    companion object {
        val EMPTY = ParivaarMix(0L, emptyList())
    }
}

class ParivaarMixStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    val mix: Flow<ParivaarMix> = dataStore.data.map { decode(it[ParivaarMixKey]) }

    private val lock = Any()

    @Volatile private var held = ParivaarMix.EMPTY
    private var settled = false

    internal val hydration: Job =
        scope.launch {
            val stored =
                try {
                    decode(dataStore.data.first()[ParivaarMixKey])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ParivaarMix.EMPTY
                }
            synchronized(lock) {
                if (!settled) {
                    settled = true
                    held = stored
                }
            }
        }

    /** Returns once the stored mix has been read, so [cached] no longer answers the empty mix only for want of reading. */
    suspend fun awaitHydrated() = hydration.join()

    suspend fun save(mix: ParivaarMix) {
        val kept = mix.copy(tracks = mix.tracks.take(ParivaarMixBuilder.MIX_MAX))
        dataStore.edit { it[ParivaarMixKey] = json.encodeToString(ParivaarMix.serializer(), kept) }
        // After the write, so a failed write leaves cached() where storage is; still before hydration can undo it.
        synchronized(lock) {
            settled = true
            held = kept
        }
    }

    /** The mix held right now. Never suspends and never touches storage or the network. */
    fun cached(): ParivaarMix = held

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): ParivaarMix =
            if (raw == null) {
                ParivaarMix.EMPTY
            } else {
                try {
                    json.decodeFromString(ParivaarMix.serializer(), raw).let { it.copy(tracks = it.tracks.take(ParivaarMixBuilder.MIX_MAX)) }
                } catch (e: Exception) {
                    ParivaarMix.EMPTY
                }
            }
    }
}

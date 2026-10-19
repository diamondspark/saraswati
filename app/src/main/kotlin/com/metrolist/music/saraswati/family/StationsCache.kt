/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the last station list the hub gave this phone, kept on
// the phone. Saral's tiles and (Task 11) the car's Stations folder read it, so
// neither ever waits for the network: [snapshot] is a plain function that
// returns at once with whatever is cached, possibly nothing yet.
//
// Writers are the phone's own launches and resumes (SaralViewModel) and the
// owner's station edits (FamilyViewModel). An empty list from the hub means
// "no stations set", not "delete the ones you have", so it is ignored: tiles
// that were working keep working if the hub is reset or misreports.
//
// Task 11: [loaded] says whether the list is the real one yet. The cache fills
// from storage a moment after the process starts, and Android Auto can ask the
// car's Stations folder before that: an empty list while not loaded means "not
// known yet", an empty list once loaded means "no stations". The car answers
// both at once (it never waits) but tells the car to ask again when loading
// finishes.
package com.metrolist.music.saraswati.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.FamilyStationsCacheKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

interface StationsCache {
    /** The cached list, then every change to it. Starts empty until storage has been read. */
    val stations: StateFlow<List<Station>>

    /**
     * False until the stored list has been read or a list has been saved, then true for good. While it is false an
     * empty [stations] only means "not read yet"; once it is true an empty one means no stations are set.
     */
    val loaded: StateFlow<Boolean>

    /** The cached list right now. Never suspends, never touches storage or the network. */
    fun snapshot(): List<Station> = stations.value

    /** Keeps [stations] as the cache. An empty list is ignored. */
    suspend fun replace(stations: List<Station>)
}

class DataStoreStationsCache(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : StationsCache {
    private val state = MutableStateFlow<List<Station>>(emptyList())
    override val stations: StateFlow<List<Station>> = state.asStateFlow()

    private val loadedState = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

    // Set by the first of: storage finishing its read, or a list being saved. Storage's read can
    // take a moment after a cold start, so a list saved in that moment must not be undone by it.
    private val lock = Any()
    private var settled = false

    internal val hydration: Job =
        scope.launch {
            val stored = readStored()
            synchronized(lock) {
                if (!settled) {
                    settled = true
                    state.value = stored
                }
                // After the list, so nobody sees "loaded" beside the list from before.
                loadedState.value = true
            }
        }

    override suspend fun replace(stations: List<Station>) {
        if (stations.isEmpty()) return
        val kept = stations.take(MAX_STATIONS)
        synchronized(lock) {
            settled = true
            state.value = kept
            loadedState.value = true
        }
        dataStore.edit { it[FamilyStationsCacheKey] = json.encodeToString(LIST, kept) }
    }

    private suspend fun readStored(): List<Station> =
        try {
            dataStore.data.first()[FamilyStationsCacheKey]
                ?.let { json.decodeFromString(LIST, it) }
                .orEmpty()
                .take(MAX_STATIONS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    private companion object {
        const val MAX_STATIONS = FamilyViewModel.MAX_STATIONS
        val json = Json { ignoreUnknownKeys = true }
        val LIST = ListSerializer(Station.serializer())
    }
}

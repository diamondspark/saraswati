/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 14 — the family playlists the hub last listed, kept on the phone so the car's Family
// folder and the Home screen show them without waiting for the network. [cached] is a plain function that returns
// at once with whatever is held, possibly nothing while storage is still being read.
//
// Unlike the stations cache, an empty list is a real answer here (the last playlist was removed), so [replace]
// keeps it. Damaged storage reads as no playlists, never a crash. A list saved while storage is still being read
// is not undone by the older stored one. At most MAX_PLAYLISTS are kept, the hub's own limit.
package com.metrolist.music.saraswati.family.playlists

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.FamilyPlaylistsCacheKey
import com.metrolist.music.saraswati.family.FamilyPlaylist
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

class FamilyPlaylistsCache(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow<List<FamilyPlaylist>>(emptyList())

    /** The cached list, then every change to it. Starts empty until storage has been read. */
    val playlists: StateFlow<List<FamilyPlaylist>> = state.asStateFlow()

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
            }
        }

    /** The list held right now. Never suspends and never touches storage or the network. */
    fun cached(): List<FamilyPlaylist> = state.value

    suspend fun replace(list: List<FamilyPlaylist>) {
        val kept = list.take(MAX_PLAYLISTS)
        synchronized(lock) {
            settled = true
            state.value = kept
        }
        dataStore.edit { it[FamilyPlaylistsCacheKey] = json.encodeToString(LIST, kept) }
    }

    private suspend fun readStored(): List<FamilyPlaylist> =
        try {
            dataStore.data.first()[FamilyPlaylistsCacheKey]
                ?.let { json.decodeFromString(LIST, it) }
                .orEmpty()
                .take(MAX_PLAYLISTS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    companion object {
        /** The hub's limit (POST /family-playlists answers 409 past it). */
        const val MAX_PLAYLISTS = 20
        private val json = Json { ignoreUnknownKeys = true }
        private val LIST = ListSerializer(FamilyPlaylist.serializer())
    }
}

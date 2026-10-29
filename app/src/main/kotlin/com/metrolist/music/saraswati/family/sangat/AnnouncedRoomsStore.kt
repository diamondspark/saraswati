/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — the room codes this phone has already announced (a notification posted, or deliberately not),
// kept across process restarts so a notification the person swiped away is not posted again for a room that is still
// going. It holds codes only, pruned to the rooms the hub lists as live every time the push handler hears from the hub.
package com.metrolist.music.saraswati.family.sangat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.SangatAnnouncedRoomsKey
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

interface AnnouncedRoomsStore {
    /** The announced codes; empty when none are stored or storage cannot be read. */
    suspend fun read(): Set<String>

    /** Replaces the set with [transform] of it, atomically. A storage failure is swallowed: announcing twice beats crashing. */
    suspend fun update(transform: (Set<String>) -> Set<String>)
}

class DataStoreAnnouncedRoomsStore(
    private val dataStore: DataStore<Preferences>,
) : AnnouncedRoomsStore {
    override suspend fun read(): Set<String> =
        try {
            dataStore.data.first()[SangatAnnouncedRoomsKey].orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

    override suspend fun update(transform: (Set<String>) -> Set<String>) {
        try {
            dataStore.edit { it[SangatAnnouncedRoomsKey] = transform(it[SangatAnnouncedRoomsKey].orEmpty()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not remembered; the room may be announced once more.
        }
    }
}

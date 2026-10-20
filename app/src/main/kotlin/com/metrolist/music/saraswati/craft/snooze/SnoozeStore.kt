/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 2 — "Abhi nahin": a song snoozed for 30 days is kept out of every queue, radio and
// automix list before it reaches the player. Stored on the phone only; no network, no YouTube dependency.
//
// [activeIds] is a plain function that answers at once from memory, so MusicService can filter without
// suspending. Before the stored list has been read it answers empty, so callers that must not miss a snooze
// use [awaitActiveIds]. Damaged storage reads as no snoozes, never a crash.
package com.metrolist.music.saraswati.craft.snooze

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class SnoozedSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val until: Long,
)

class SnoozeStore(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long,
    scope: CoroutineScope,
) {
    /** Snoozes still in force, soonest wake-up first. */
    val snoozed: Flow<List<SnoozedSong>> =
        dataStore.data.map { active(decode(it[Key]), clock()).sortedBy { song -> song.until } }

    private val lock = Any()
    private val writes = Mutex()

    @Volatile private var held: List<SnoozedSong> = emptyList()
    private var settled = false

    private val hydration: Job =
        scope.launch {
            val stored =
                try {
                    decode(dataStore.data.first()[Key])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            synchronized(lock) {
                if (!settled) {
                    settled = true
                    held = stored
                }
            }
        }

    /** Returns once the stored snoozes have been read. */
    suspend fun awaitHydrated() = hydration.join()

    /** Video ids snoozed right now. Never suspends and never touches storage or the network. */
    fun activeIds(): Set<String> = active(held, clock()).mapTo(HashSet()) { it.videoId }

    /** [activeIds] once the stored snoozes have been read, so a snooze made before a restart still applies. */
    suspend fun awaitActiveIds(): Set<String> {
        awaitHydrated()
        return activeIds()
    }

    /** Snoozes [videoId] for [SNOOZE_DAYS] from now. Snoozing a song that is already snoozed starts the count again. */
    suspend fun snooze(
        videoId: String,
        title: String,
        artist: String,
    ) {
        val until = clock() + SNOOZE_DAYS * DAY_MS
        write { list -> list.filterNot { it.videoId == videoId } + SnoozedSong(videoId, title, artist, until) }
    }

    /** Ends the snooze on [videoId] at once. */
    suspend fun wake(videoId: String) {
        write { list -> list.filterNot { it.videoId == videoId } }
    }

    private suspend fun write(change: (List<SnoozedSong>) -> List<SnoozedSong>) {
        writes.withLock {
            // After hydration, so a write made before the stored list is read is never undone by it.
            awaitHydrated()
            val now = clock()
            val kept =
                change(active(held, now))
                    .sortedBy { it.until }
                    .let { list -> if (list.size > SNOOZE_MAX) list.drop(list.size - SNOOZE_MAX) else list }
            try {
                dataStore.edit { it[Key] = json.encodeToString(LIST, kept) }
            } catch (e: IOException) {
                // A failed write is dropped: memory keeps what storage still has, and nothing crashes.
                return@withLock
            }
            // After the write, so a failed write leaves the memory copy where storage is.
            synchronized(lock) {
                settled = true
                held = kept
            }
        }
    }

    companion object {
        const val SNOOZE_DAYS = 30
        const val SNOOZE_MAX = 500
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Stored snoozes. Public so BackupPolicy can keep listening history out of backups. */
        val Key = stringPreferencesKey("sarCraftSnoozes")
        private val LIST = ListSerializer(SnoozedSong.serializer())
        private val json = Json { ignoreUnknownKeys = true }

        private fun active(
            list: List<SnoozedSong>,
            now: Long,
        ): List<SnoozedSong> = list.filter { it.until > now }

        private fun decode(raw: String?): List<SnoozedSong> =
            if (raw == null) {
                emptyList()
            } else {
                try {
                    json.decodeFromString(LIST, raw).filter { it.videoId.isNotBlank() }
                } catch (e: Exception) {
                    emptyList()
                }
            }
    }
}

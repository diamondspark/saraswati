/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 4 — keeps one resume point per speed-dial collection, the most recently saved
// [RESUME_MAX] at most. [get] answers at once from memory for the Speed Dial tiles; [awaitHydrated] covers the
// first read after a cold start. Damaged storage reads as no points.
package com.metrolist.music.saraswati.craft.resume

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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

class ResumeStore(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long,
    scope: CoroutineScope,
) {
    /** Every stored point, keyed by collection id. */
    val points: Flow<Map<String, ResumePoint>> =
        dataStore.data.map { decode(it[PointsKey]).associateBy { point -> point.sourceId } }

    private val lock = Any()
    private val writes = Mutex()

    @Volatile private var held: Map<String, ResumePoint> = emptyMap()
    private var settled = false

    private val hydration: Job =
        scope.launch {
            val stored =
                try {
                    decode(dataStore.data.first()[PointsKey]).associateBy { it.sourceId }
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

    /** Returns once the stored points have been read. */
    suspend fun awaitHydrated() = hydration.join()

    /** The point held for [sourceId] right now. Never suspends and never touches storage or the network. */
    fun get(sourceId: String): ResumePoint? = held[sourceId]

    /** Keeps [point] as the one for its collection, replacing an earlier one. */
    suspend fun save(point: ResumePoint) {
        write { map -> map + (point.sourceId to point) }
    }

    /** Forgets the point for [sourceId]. */
    suspend fun clear(sourceId: String) {
        write { map -> map - sourceId }
    }

    private suspend fun write(change: (Map<String, ResumePoint>) -> Map<String, ResumePoint>) {
        writes.withLock {
            // After hydration, so a write made before the stored points are read is never undone by them.
            awaitHydrated()
            val changed = change(held)
            val kept =
                changed.values
                    .sortedBy { it.savedAt }
                    .let { list -> if (list.size > RESUME_MAX) list.drop(list.size - RESUME_MAX) else list }
            dataStore.edit { it[PointsKey] = json.encodeToString(LIST, kept) }
            // After the write, so a failed write leaves the memory copy where storage is.
            synchronized(lock) {
                settled = true
                held = kept.associateBy { it.sourceId }
            }
        }
    }

    companion object {
        /** Speed Dial shows at most 27 tiles; the least recently saved point is dropped past that. */
        const val RESUME_MAX = 27
        const val RESUME_TTL_DAYS = 30

        private val PointsKey = stringPreferencesKey("sarCraftResumePoints")
        private val LIST = ListSerializer(ResumePoint.serializer())
        private val json = Json { ignoreUnknownKeys = true }

        private fun decode(raw: String?): List<ResumePoint> =
            if (raw == null) {
                emptyList()
            } else {
                try {
                    json.decodeFromString(LIST, raw).filter { it.sourceId.isNotBlank() }
                } catch (e: Exception) {
                    emptyList()
                }
            }
    }
}

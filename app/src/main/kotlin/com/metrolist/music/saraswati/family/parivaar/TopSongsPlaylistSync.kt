/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — keeps one unlisted YouTube playlist, "Saraswati · <name> top songs", equal to this
// phone's 30 most-played songs of the last 90 days (never local files), and tells the hub which playlist it is so
// the other phones can build the Parivaar Mix from it.
//
// Weekly: due 7 days after the last sync that finished AND was registered with the hub. A sync is a diff, not a
// rebuild: songs that dropped out are removed, new ones are appended in rank order, so a week costs at most 30
// adds and 30 removes, with EDIT_CALL_SPACING_MS between edit calls to stay well inside what YouTube tolerates.
//
// The playlist id is stored the moment the playlist exists, before anything is added, so a failure part-way (no
// network, a hub that is down) never makes a second playlist: the next launch reads the playlist, finishes the
// diff and registers it. A playlist that YouTube says no longer exists is replaced once, by a fresh one.
//
// Never throws except to pass on a cancellation. A phone with no session, the Parivaar Mix switched off by the
// owner, or nothing played yet does nothing and succeeds. After a failure this process waits RETRY_GAP_MS before
// trying again (this runs on every resume of the app); a new process always tries.
package com.metrolist.music.saraswati.family.parivaar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.ParivaarPlaylistIdKey
import com.metrolist.music.constants.ParivaarTopSongsSyncedAtKey
import com.metrolist.music.saraswati.family.HubIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class TopSongsPlaylistSync(
    private val port: YouTubePlaylistPort,
    private val hub: ParivaarHub,
    private val identity: HubIdentity,
    private val songs: TopSongsSource,
    private val dataStore: DataStore<Preferences>,
    private val enabled: () -> Boolean = { true },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val running = Mutex()
    private val backoff = AttemptBackoff(RETRY_GAP_MS)

    suspend fun syncIfDue(
        now: Instant,
        memberName: String,
    ): Result<Unit> {
        if (!running.tryLock()) return Result.success(Unit)
        try {
            return run(now, memberName)
        } finally {
            running.unlock()
        }
    }

    private suspend fun run(
        now: Instant,
        memberName: String,
    ): Result<Unit> {
        val nowMs = now.toEpochMilli()
        return try {
            if (!enabled()) return Result.success(Unit)
            val token = identity.sessionToken() ?: return Result.success(Unit)
            val stored = dataStore.data.first()
            val storedId = stored[ParivaarPlaylistIdKey]
            val syncedAt = stored[ParivaarTopSongsSyncedAtKey]
            if (storedId != null && syncedAt != null && nowMs - syncedAt in 0 until SYNC_INTERVAL_MS) return Result.success(Unit)
            if (!backoff.allowed(nowMs)) return Result.success(Unit)

            val wanted =
                songs
                    .top(now.minus(Duration.ofDays(WINDOW_DAYS)), FETCH_LIMIT)
                    .filterNot { it.isLocal }
                    .map { it.track }
                    .distinctBy { it.videoId }
                    .take(TOP_SONGS_LIMIT)
            if (wanted.isEmpty() && storedId == null) return Result.success(Unit)

            var edited = false
            suspend fun spaced() {
                if (edited) pause(EDIT_CALL_SPACING_MS)
                edited = true
            }

            suspend fun create(): String {
                val id = port.createUnlisted(title(memberName)).getOrThrow()
                edited = true
                dataStore.edit {
                    it[ParivaarPlaylistIdKey] = id
                    it.remove(ParivaarTopSongsSyncedAtKey)
                }
                return id
            }

            val playlistId: String
            val current: List<PlaylistEntry>
            if (storedId == null) {
                playlistId = create()
                current = emptyList()
            } else {
                val read = port.entries(storedId)
                if (read.isSuccess) {
                    playlistId = storedId
                    current = read.getOrThrow()
                } else if (read.exceptionOrNull() is PlaylistGoneException) {
                    playlistId = create()
                    current = emptyList()
                } else {
                    throw read.exceptionOrNull() ?: IllegalStateException("playlist read failed")
                }
            }

            val keep = wanted.mapTo(HashSet()) { it.videoId }
            val kept = HashSet<String>()
            val drop = current.filterNot { it.videoId in keep && kept.add(it.videoId) }
            val add = wanted.filter { it.videoId !in kept }
            for (entry in drop) {
                spaced()
                port.remove(playlistId, entry.videoId, entry.setVideoId).getOrThrow()
            }
            for (track in add) {
                spaced()
                port.add(playlistId, track.videoId).getOrThrow()
            }

            hub.register(token, playlistId, memberName.trim().take(MAX_MEMBER_NAME)).getOrThrow()
            dataStore.edit { it[ParivaarTopSongsSyncedAtKey] = nowMs }
            backoff.finished()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            backoff.failed(nowMs)
            Result.failure(e)
        }
    }

    companion object {
        const val TOP_SONGS_LIMIT = 30
        const val WINDOW_DAYS = 90L
        const val EDIT_CALL_SPACING_MS = 500L

        // Local files can sit among the most played; ask for more than 30 so dropping them still leaves 30.
        private const val FETCH_LIMIT = TOP_SONGS_LIMIT * 2
        private const val SYNC_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val RETRY_GAP_MS = 30 * 60 * 1000L
        private const val MAX_MEMBER_NAME = 50

        fun title(memberName: String) = "Saraswati · $memberName top songs"
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — rebuilds the Parivaar Mix once a day: asks the hub for every member's playlist
// (this phone's included, so every phone feeds the builder the same input), reads each playlist from YouTube,
// builds the mix, keeps it on the phone and, on an unmetered network, downloads what is missing.
//
// A member whose playlist cannot be read is left out of this build rather than failing it. Radio is requested
// only when the members share a song to start it from (ParivaarMixBuilder does not check). A build that comes out
// empty (fewer than two members with songs, usually a read that failed) is not saved over the mix already held and
// does not start the 24 hours, so the next launch tries again.
//
// Does nothing, successfully, when the owner has switched the Parivaar Mix off, when the phone has no session, or
// when the held mix is under REFRESH_INTERVAL_MS old. Never throws except to pass on a cancellation. After a
// failure this process waits RETRY_GAP_MS before trying again (this runs on every resume); a new process tries.
package com.metrolist.music.saraswati.family.parivaar

import com.metrolist.music.saraswati.family.HubIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class ParivaarMixRefresher(
    private val hub: ParivaarHub,
    private val port: YouTubePlaylistPort,
    private val identity: HubIdentity,
    private val store: ParivaarMixStore,
    private val downloader: MixDownloader,
    private val enabled: suspend () -> Boolean = { true },
) {
    private val running = Mutex()
    private val backoff = AttemptBackoff(RETRY_GAP_MS)

    suspend fun refreshIfDue(now: Instant): Result<Unit> {
        if (!running.tryLock()) return Result.success(Unit)
        try {
            return run(now)
        } finally {
            running.unlock()
        }
    }

    private suspend fun run(now: Instant): Result<Unit> {
        val nowMs = now.toEpochMilli()
        return try {
            if (!enabled()) return Result.success(Unit)
            val token = identity.sessionToken() ?: return Result.success(Unit)
            val held = store.mix.first()
            if (nowMs - held.builtAt in 0 until REFRESH_INTERVAL_MS) return Result.success(Unit)
            if (!backoff.allowed(nowMs)) return Result.success(Unit)

            val rows = hub.playlists(token).getOrThrow()
            val members =
                rows.mapNotNull { row ->
                    port.entries(row.playlistId).getOrNull()?.let { entries ->
                        MemberTopSongs(row.deviceId, row.memberName, entries.map { it.track })
                    }
                }
            val seed = ParivaarMixBuilder.radioSeed(members)
            val radio = seed?.let { port.radio(it).getOrNull() }.orEmpty()
            val tracks = ParivaarMixBuilder.build(members, radio)
            if (tracks.isEmpty()) {
                backoff.failed(nowMs)
                return Result.success(Unit)
            }

            store.save(ParivaarMix(nowMs, tracks))
            downloader.downloadMissing(tracks)
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
        const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val RETRY_GAP_MS = 30 * 60 * 1000L
    }
}

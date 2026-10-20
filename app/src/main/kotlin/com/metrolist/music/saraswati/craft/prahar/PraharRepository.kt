/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 7 — builds the Prahar mix once per window per day. Own songs come from this person's plays in
// the current window (local songs excluded by the query); one YouTube radio call per build seeds the rest. A radio
// failure saves an own-songs-only mix. Fewer than MIN_OWN own songs saves nothing and makes no radio call.
package com.metrolist.music.saraswati.craft.prahar

import com.metrolist.music.db.entities.Song
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.YouTubePlaylistPort
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** What the repository needs from the database, as an interface so it runs against a fake in plain JUnit. */
interface PraharDao {
    suspend fun praharPlays(fromTimeStamp: LocalDateTime): List<PraharEventRow>

    suspend fun songsByIds(ids: List<String>): List<Song>
}

class PraharRepository(
    private val dao: PraharDao,
    private val port: YouTubePlaylistPort,
    private val store: PraharStore,
    private val snoozed: suspend () -> Set<String> = { emptySet() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    // SARASWATI: Phase 6 fix (AB-I5) — two browses at once must not both pay for a radio call.
    private val refreshes = Mutex()

    /** Builds and saves the mix for [now]'s window and day unless one is already held for them. */
    suspend fun refreshIfDue(now: LocalDateTime): Result<Unit> =
        try {
            refreshes.withLock {
                val prahar = Prahar.of(now.toLocalTime())
                val day = now.toLocalDate()
                store.awaitHydrated()
                val held = store.cached()
                if (held != null && held.prahar == prahar && held.day == day) {
                    Result.success(Unit)
                } else {
                    build(now, prahar, day)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun build(
        now: LocalDateTime,
        prahar: Prahar,
        day: LocalDate,
    ): Result<Unit> {
        val excluded = snoozed()
        val plays =
            dao.praharPlays(now.minusDays(PraharMixBuilder.LOOKBACK_DAYS.toLong())).map {
                PraharPlay(it.songId, it.timestamp, it.playTime)
            }
        val ownIds = PraharMixBuilder.ownSongs(plays, prahar, excluded)
        if (ownIds.size < PraharMixBuilder.MIN_OWN) return Result.success(Unit)

        val byId = dao.songsByIds(ownIds).associateBy { it.song.id }
        val own = ownIds.mapNotNull { byId[it]?.toMixTrack() }
        val seed = PraharMixBuilder.radioSeed(ownIds, day)
        val radio = seed?.let { port.radio(it).getOrNull() }.orEmpty()
        val tracks = PraharMixBuilder.build(own, radio, excluded)
        if (tracks.isNotEmpty()) {
            store.save(PraharMix(prahar, day, nowMillis(), tracks))
        }
        return Result.success(Unit)
    }
}

internal fun Song.toMixTrack(): MixTrack =
    MixTrack(
        videoId = song.id,
        title = song.title,
        artist = artists.joinToString(", ") { it.name },
        thumbnailUrl = song.thumbnailUrl,
        durationMs = song.duration.takeIf { it > 0 }?.let { it * 1000L },
    )

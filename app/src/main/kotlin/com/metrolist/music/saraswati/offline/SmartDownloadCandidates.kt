/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 11 — the songs "Raat ke Gaane" may save, before the plan picks from them: the Parivaar
// Mix (only while the owner has it on), a few songs for each family Station, what this phone plays most, and
// what it liked last. Station songs are chosen here and written to [StationOfflineStore], which is what lets a
// Station play with no network (Task 9). Songs on this phone never appear: they never leave it.
//
// Look human: a Station's radio is fetched only when its saved set is a week old, and fetches are SPACING_MS apart.
package com.metrolist.music.saraswati.offline

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
import com.metrolist.music.saraswati.family.parivaar.TopSongsSource
import com.metrolist.music.saraswati.family.parivaar.toMediaMetadata
import com.metrolist.music.saraswati.family.parivaar.YouTubePlaylistPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

fun interface CandidateGatherer {
    suspend fun gather(now: Instant): List<Candidate>
}

/** What the candidates need from the song library. */
interface SmartLibrary {
    /** The phone's own row for [id], so a song it already knows is never overwritten with thinner metadata. */
    suspend fun stored(id: String): MediaMetadata?

    /** The latest [limit] liked songs, newest first. */
    suspend fun liked(limit: Int): List<MediaMetadata>
}

class DatabaseSmartLibrary(
    private val database: MusicDatabase,
) : SmartLibrary {
    override suspend fun stored(id: String): MediaMetadata? = database.getSongByIdBlocking(id)?.takeUnless { it.song.isLocal }?.toMediaMetadata()

    override suspend fun liked(limit: Int): List<MediaMetadata> =
        database.likedSongsByCreateDateDesc(limit, 0).filterNot { it.song.isLocal }.map { it.toMediaMetadata() }
}

class SmartDownloadCandidates(
    private val mixStore: ParivaarMixStore,
    private val stationsCache: StationsCache,
    private val stationStore: StationOfflineStore,
    private val port: YouTubePlaylistPort,
    private val topSongs: TopSongsSource,
    private val library: SmartLibrary,
    private val mixEnabled: () -> Boolean,
    private val onFetchFailed: suspend (Instant) -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : CandidateGatherer {
    override suspend fun gather(now: Instant): List<Candidate> {
        val mix = if (mixEnabled()) mixCandidates() else emptyList()
        return mix + stationCandidates(now) + topPlayedCandidates(now) + likedCandidates()
    }

    private suspend fun mixCandidates(): List<Candidate> {
        mixStore.awaitHydrated()
        return mixStore.cached().tracks.map { Candidate(metadataOf(it), CandidateSource.PARIVAAR_MIX) }
    }

    private suspend fun stationCandidates(now: Instant): List<Candidate> {
        withTimeoutOrNull(HYDRATION_WAIT_MS) { stationsCache.loaded.first { it } }
        stationStore.hydration.join()
        val stations = stationsCache.snapshot()
        if (stations.isEmpty()) return emptyList()
        stationStore.retain(stations)

        var fetched = false
        stations.forEach { station ->
            if (!needsRefresh(station, now) || attemptedRecently(station, now)) return@forEach
            if (fetched) pause(SmartDownloadRunner.SPACING_MS)
            fetched = true
            // Recorded before the ask, so a fetch that fails or is cut short is not asked again for a day.
            stationStore.markAttempted(station, now.toEpochMilli())
            val answer = port.radio(station.seedVideoId)
            if (answer.isFailure) onFetchFailed(now)
            val tracks = answer.getOrNull().orEmpty().take(STATION_OFFLINE_TRACKS)
            // A failed or empty answer keeps the set already held; a Station is never left with nothing.
            if (tracks.isNotEmpty()) {
                stationStore.save(StationOfflineSet(station.id, station.seedVideoId, tracks, now.toEpochMilli(), now.toEpochMilli()))
            }
        }
        return stations.flatMap { station ->
            stationStore.cached()[station.id]
                ?.takeIf { it.seedVideoId == station.seedVideoId }
                ?.tracks
                .orEmpty()
                .take(STATION_OFFLINE_TRACKS)
                .map { Candidate(metadataOf(it), CandidateSource.STATION) }
        }
    }

    private suspend fun topPlayedCandidates(now: Instant): List<Candidate> =
        topSongs
            .top(now.minus(Duration.ofDays(TOP_PLAYED_DAYS)), TOP_PLAYED_COUNT)
            .filterNot { it.isLocal }
            .map { Candidate(metadataOf(it.track), CandidateSource.TOP_PLAYED) }

    private suspend fun likedCandidates(): List<Candidate> = library.liked(LIKED_COUNT).map { Candidate(it, CandidateSource.LIKED) }

    private fun attemptedRecently(
        station: Station,
        now: Instant,
    ): Boolean {
        val last = stationStore.cached()[station.id]?.takeIf { it.seedVideoId == station.seedVideoId }?.lastAttemptAt ?: return false
        return now.toEpochMilli() - last < ATTEMPT_BACKOFF_MS
    }

    private fun needsRefresh(
        station: Station,
        now: Instant,
    ): Boolean {
        val held = stationStore.cached()[station.id]?.takeIf { it.seedVideoId == station.seedVideoId } ?: return true
        return held.tracks.isEmpty() || now.toEpochMilli() - held.refreshedAt >= Duration.ofDays(STATION_REFRESH_DAYS).toMillis()
    }

    private suspend fun metadataOf(track: MixTrack): MediaMetadata = library.stored(track.videoId) ?: track.toMediaMetadata()

    companion object {
        const val STATION_OFFLINE_TRACKS = 8
        const val TOP_PLAYED_COUNT = 30
        const val TOP_PLAYED_DAYS = 30L
        const val LIKED_COUNT = 20
        const val STATION_REFRESH_DAYS = 7L
        private val ATTEMPT_BACKOFF_MS = Duration.ofDays(1).toMillis()
        private const val HYDRATION_WAIT_MS = 5_000L
    }
}

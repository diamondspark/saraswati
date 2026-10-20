/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — this phone's most-played songs, best first, as the weekly playlist sync reads them.
package com.metrolist.music.saraswati.family.parivaar

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A song with the one fact the sync filters on. */
data class RankedSong(
    val track: MixTrack,
    val isLocal: Boolean,
)

interface TopSongsSource {
    /** Up to [limit] songs played since [since], most played first. May include local files; the caller drops them. */
    suspend fun top(
        since: Instant,
        limit: Int,
    ): List<RankedSong>
}

class DatabaseTopSongsSource(
    private val database: MusicDatabase,
) : TopSongsSource {
    override suspend fun top(
        since: Instant,
        limit: Int,
    ): List<RankedSong> {
        val from = LocalDateTime.ofInstant(since, ZoneId.systemDefault())
        val until = LocalDateTime.now()
        val songs = database.mostPlayedSongs(fromTimeStamp = from, limit = limit, toTimeStamp = until).first()
        // mostPlayedSongs picks the right songs but does not promise an order; the stats query sorts by time listened.
        val rank =
            database
                .mostPlayedSongsStats(fromTimeStamp = from, limit = limit, toTimeStamp = until)
                .first()
                .withIndex()
                .associate { (index, stat) -> stat.id to index }
        return songs.sortedBy { rank[it.id] ?: Int.MAX_VALUE }.map { RankedSong(it.toMixTrack(), it.song.isLocal) }
    }
}

private fun Song.toMixTrack(): MixTrack =
    MixTrack(
        videoId = song.id,
        title = song.title,
        artist = artists.joinToString(", ") { it.name },
        thumbnailUrl = song.thumbnailUrl,
        durationMs = song.duration.takeIf { it > 0 }?.let { it * 1000L },
    )

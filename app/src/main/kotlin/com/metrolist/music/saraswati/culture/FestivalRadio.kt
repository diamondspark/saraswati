/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 10 — a festival's one-tap radio, the same on Home and in
// Saral. A station tile is seeded from a fixed video; a festival has no fixed
// video, only a search ("Diwali songs"), so the seed is the first song that
// search finds. From there it is the very radio a tile starts: the seed
// video with its RDAMVM mix playlist. It is a one-off suggestion, never one of
// the owner's stations, so it takes no station slot.
package com.metrolist.music.saraswati.culture

import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.saraswati.saral.PlaybackFailures
import com.metrolist.music.saraswati.saral.SaralSearch

/** What looking for a festival radio's seed song came to. */
sealed interface FestivalSeed {
    data class Found(
        val videoId: String,
    ) : FestivalSeed

    /** The search ran and had no song. */
    data object NotFound : FestivalSeed

    /** The search could not run: [offline] when the cause was the network. */
    data class Failed(
        val offline: Boolean,
    ) : FestivalSeed
}

class FestivalRadio(
    private val search: SaralSearch,
) {
    suspend fun seedFor(festival: Festival): FestivalSeed {
        val answer = search.firstSong(festival.radioQueryHi)
        answer.exceptionOrNull()?.let { return FestivalSeed.Failed(PlaybackFailures.isOffline(it)) }
        val song = answer.getOrNull() ?: return FestivalSeed.NotFound
        return FestivalSeed.Found(song.id)
    }
}

/** The radio of one song: the song with its mix playlist, as "Start radio" and a station tile build it. */
fun radioEndpointFor(seedVideoId: String): WatchEndpoint = WatchEndpoint(videoId = seedVideoId, playlistId = "RDAMVM$seedVideoId")

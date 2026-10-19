/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the real player and search behind Saral's two small
// interfaces. Thin on purpose: SaralViewModelTest covers the decisions against
// fakes; this only translates them into the calls the rest of the app makes.
//  - A station tile is a YouTube radio: the seed video with its RDAMVM mix
//    playlist, the same endpoint YouTubeQueue.radio builds for "Start radio".
//  - A spoken song is queued the way a tapped search result is (the song, then
//    its radio via YouTubeQueue's default), after a plain song search that is
//    not recorded in search history.
package com.metrolist.music.saraswati.saral

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

internal class PlayerConnectionPlayback(
    private val connection: PlayerConnection,
) : SaralPlayback {
    override val isPlaying: StateFlow<Boolean> get() = connection.isEffectivelyPlaying

    override fun hasQueue(): Boolean = runCatching { connection.player.currentMediaItem != null }.getOrDefault(false)

    override fun playRadio(seedVideoId: String) {
        attempt("playRadio") {
            connection.playQueue(YouTubeQueue(WatchEndpoint(videoId = seedVideoId, playlistId = "RDAMVM$seedVideoId")))
        }
    }

    override fun playSong(song: SongItem) {
        attempt("playSong") {
            connection.playQueue(YouTubeQueue(WatchEndpoint(videoId = song.id), song.toMediaMetadata()))
        }
    }

    override fun togglePlayPause() = connection.togglePlayPause()

    override fun skipNext() = connection.seekToNext()

    override fun skipPrevious() = connection.seekToPrevious()

    private fun attempt(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            Timber.tag("Saral").w(e, "$what failed")
        }
    }
}

class YouTubeSaralSearch : SaralSearch {
    override suspend fun firstSong(query: String): Result<SongItem?> =
        YouTube
            .search(query, YouTube.SearchFilter.FILTER_SONG)
            .map { result -> result.items.filterIsInstance<SongItem>().firstOrNull() }
}

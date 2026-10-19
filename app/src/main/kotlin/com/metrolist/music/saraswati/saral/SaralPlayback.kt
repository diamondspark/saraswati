/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the real player and search behind Saral's small
// interfaces. Thin on purpose: SaralViewModelTest covers the decisions against
// fakes; this only translates them into the calls the rest of the app makes.
//  - A station tile is a YouTube radio: the seed video with its RDAMVM mix
//    playlist, the same endpoint YouTubeQueue.radio builds for "Start radio".
//  - A spoken song is queued the way a tapped search result is (the song, then
//    its radio via YouTubeQueue's default), after a plain song search that is
//    not recorded in search history.
//
// Fix round 1: the state it reports is the player's own (current track,
// playing or not, failing, waiting for a connection), joined with a QueueWatch
// on the queue Saral last started, which is how a queue that never loaded and
// the question "is this track ours" become visible.
package com.metrolist.music.saraswati.saral

import androidx.media3.common.PlaybackException
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import timber.log.Timber

/** What the player is doing, apart from whose queue it is. */
private data class PlayerSnapshot(
    val mediaId: String?,
    val title: String?,
    val playing: Boolean,
    val problem: PlaybackProblem?,
)

internal class PlayerConnectionPlayback(
    private val connection: PlayerConnection,
) : SaralPlayback {
    private val watch = MutableStateFlow(QueueWatch())

    private val snapshot: Flow<PlayerSnapshot> =
        combine(
            connection.mediaMetadata,
            connection.isEffectivelyPlaying,
            connection.waitingForNetworkConnection,
            connection.error,
        ) { metadata, playing, waiting, error ->
            PlayerSnapshot(metadata?.id, metadata?.title, playing, playerProblem(waiting, error))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: Flow<SaralPlayerState> =
        watch.flatMapLatest { queue ->
            combine(snapshot, queue.ids, queue.problem) { player, ids, queueProblem ->
                SaralPlayerState(
                    mediaId = player.mediaId,
                    title = player.title,
                    playing = player.playing,
                    ours = player.mediaId != null && player.mediaId in ids,
                    problem = queueProblem ?: player.problem,
                )
            }
        }

    override fun hasQueue(): Boolean = runCatching { connection.player.currentMediaItem != null }.getOrDefault(false)

    override fun playRadio(seedVideoId: String) {
        start("playRadio", YouTubeQueue(WatchEndpoint(videoId = seedVideoId, playlistId = "RDAMVM$seedVideoId")))
    }

    override fun playSong(song: SongItem) {
        start("playSong", YouTubeQueue(WatchEndpoint(videoId = song.id), song.toMediaMetadata()))
    }

    override fun togglePlayPause() = connection.togglePlayPause()

    override fun skipNext() = connection.seekToNext()

    override fun skipPrevious() = connection.seekToPrevious()

    private fun start(
        what: String,
        queue: Queue,
    ) {
        val fresh = QueueWatch()
        watch.value = fresh
        try {
            connection.playQueue(fresh.wrap(queue))
        } catch (e: Exception) {
            Timber.tag("Saral").w(e, "$what failed")
            fresh.record(e)
        }
    }

    private fun playerProblem(
        waitingForNetwork: Boolean,
        error: PlaybackException?,
    ): PlaybackProblem? =
        when {
            waitingForNetwork -> PlaybackProblem.OFFLINE
            error == null -> null
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackProblem.OFFLINE
            else -> PlaybackProblem.COULD_NOT_PLAY
        }
}

class YouTubeSaralSearch : SaralSearch {
    override suspend fun firstSong(query: String): Result<SongItem?> =
        YouTube
            .search(query, YouTube.SearchFilter.FILTER_SONG)
            .map { result -> result.items.filterIsInstance<SongItem>().firstOrNull() }
}

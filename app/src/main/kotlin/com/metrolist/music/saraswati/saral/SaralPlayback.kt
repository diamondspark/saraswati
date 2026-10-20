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
//
// Fix wave B2: that QueueWatch lives in QueueWatchHolder.process, not in this adapter, so a
// rotation (a new adapter over the same player) keeps knowing which tracks are Saral's. A
// problem the player reports (an error, waiting for a connection) shows only once it has
// lasted PLAYER_PROBLEM_DELAY_MS: ExoPlayer recovers from a dropped connection or a retried
// load by itself, and the error card used to flash for that moment. A queue that could not
// load at all (QueueWatch.problem) is Saral's own finding and shows at once.
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import timber.log.Timber

/** What the player is doing, apart from whose queue it is. */
internal data class PlayerSnapshot(
    val mediaId: String?,
    val title: String?,
    val playing: Boolean,
    val problem: PlaybackProblem?,
)

/** How long a problem the player reports must last before Saral says so. */
internal const val PLAYER_PROBLEM_DELAY_MS = 1_800L

/**
 * A problem shows only if it is still there after [delayMs]: one that clears sooner is never emitted. Going from
 * a problem to none is immediate, and a change from one problem to another keeps the old one on until the new one
 * has lasted. The first value is always "none", at once, even if a problem is already there: a combine that waits
 * for this flow (the whole player snapshot) is then not held back when a screen binds during a problem.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<PlaybackProblem?>.lasting(delayMs: Long): Flow<PlaybackProblem?> =
    distinctUntilChanged()
        .transformLatest { problem ->
            if (problem != null) delay(delayMs)
            emit(problem)
        }.onStart { emit(null) }
        .distinctUntilChanged()

/** What the player is doing, joined with the queue Saral last started (and so with whose track it is). */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun saralPlayerState(
    watches: QueueWatchHolder,
    snapshot: Flow<PlayerSnapshot>,
): Flow<SaralPlayerState> =
    watches.watch.flatMapLatest { queue ->
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

internal class PlayerConnectionPlayback(
    private val connection: PlayerConnection,
    private val watches: QueueWatchHolder = QueueWatchHolder.process,
) : SaralPlayback {
    private val lastingProblem: Flow<PlaybackProblem?> =
        combine(connection.waitingForNetworkConnection, connection.error) { waiting, error -> playerProblem(waiting, error) }
            .lasting(PLAYER_PROBLEM_DELAY_MS)

    private val snapshot: Flow<PlayerSnapshot> =
        combine(connection.mediaMetadata, connection.isEffectivelyPlaying, lastingProblem) { metadata, playing, problem ->
            PlayerSnapshot(metadata?.id, metadata?.title, playing, problem)
        }

    override val state: Flow<SaralPlayerState> = saralPlayerState(watches, snapshot)

    override fun hasQueue(): Boolean = runCatching { connection.player.currentMediaItem != null }.getOrDefault(false)

    override fun playRadio(seedVideoId: String) {
        start("playRadio", connection.service.stationQueue(seedVideoId)) // SARASWATI: Phase 5 Task 9 — offline-first Station
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
        val fresh = watches.startNew()
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

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — what the Parivaar code needs from YouTube, as an interface so it runs against a fake
// in plain JUnit. [InnerTubePlaylistPort] is the only implementation. Every function returns a Result and never
// throws, a cancellation aside. A playlist that no longer exists on YouTube reads as [PlaylistGoneException], so the
// caller can tell "deleted" from "no network".
package com.metrolist.music.saraswati.family.parivaar

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertubex.InnerTubeHttpException
import io.ktor.http.HttpStatusCode
import kotlin.coroutines.cancellation.CancellationException

/** One song in a playlist: the track, and the id YouTube needs to remove this one occurrence. */
data class PlaylistEntry(
    val setVideoId: String,
    val track: MixTrack,
) {
    val videoId: String get() = track.videoId
}

/** The playlist is gone from YouTube (deleted, or not visible to this account any more). */
class PlaylistGoneException(
    message: String,
) : Exception(message)

/** YouTube permanently refuses this song (removed, region-blocked, not addable); retrying will not help. */
class SongRejectedException(
    message: String,
) : Exception(message)

interface YouTubePlaylistPort {
    /** Makes a new UNLISTED playlist on the signed-in account and returns its id. */
    suspend fun createUnlisted(title: String): Result<String>

    /** Every song in the playlist, in playlist order. [PlaylistGoneException] when it no longer exists. */
    suspend fun entries(playlistId: String): Result<List<PlaylistEntry>>

    /** [SongRejectedException] when YouTube permanently refuses [videoId]. */
    suspend fun add(
        playlistId: String,
        videoId: String,
    ): Result<Unit>

    suspend fun remove(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ): Result<Unit>

    suspend fun delete(playlistId: String): Result<Unit>

    /** YouTube Music's radio for [seedVideoId]: the first page of "RDAMVM<seed>". */
    suspend fun radio(seedVideoId: String): Result<List<MixTrack>>
}

class InnerTubePlaylistPort : YouTubePlaylistPort {
    override suspend fun createUnlisted(title: String): Result<String> =
        guarded {
            requireSignedIn()
            YouTube.createPlaylist(title, PRIVACY_UNLISTED)
        }

    override suspend fun entries(playlistId: String): Result<List<PlaylistEntry>> =
        guarded {
            // Reading needs no sign-in: an unlisted playlist opens by its link.
            val first = YouTube.playlist(playlistId).getOrElse { throw it.asGoneIfMissing(playlistId) }
            val songs = ArrayList(first.songs)
            var continuation = first.songsContinuation
            var pages = 0
            while (continuation != null && pages++ < MAX_PAGES) {
                val page = YouTube.playlistContinuation(continuation).getOrThrow()
                songs += page.songs
                continuation = page.continuation
            }
            songs.mapNotNull { song -> song.setVideoId?.let { PlaylistEntry(it, song.toMixTrack()) } }
        }

    override suspend fun add(
        playlistId: String,
        videoId: String,
    ): Result<Unit> =
        guarded {
            requireSignedIn()
            YouTube.addToPlaylist(playlistId, videoId).getOrElse { throw it.asRejectedIfPermanent(videoId) }
            Unit
        }

    override suspend fun remove(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ): Result<Unit> =
        guarded {
            requireSignedIn()
            YouTube.removeFromPlaylist(playlistId, videoId, setVideoId).getOrThrow()
            Unit
        }

    override suspend fun delete(playlistId: String): Result<Unit> =
        guarded {
            requireSignedIn()
            YouTube.deletePlaylist(playlistId).getOrThrow()
            Unit
        }

    override suspend fun radio(seedVideoId: String): Result<List<MixTrack>> =
        guarded {
            YouTube
                .next(WatchEndpoint(videoId = seedVideoId, playlistId = "RDAMVM$seedVideoId"))
                .getOrThrow()
                .items
                .map { it.toMixTrack() }
        }

    private fun requireSignedIn() {
        check(!YouTube.cookie.isNullOrBlank()) { "not signed in to YouTube" }
    }

    // 400 and 404 name this one song; an auth failure (401, 403), a rate limit (429) or a server error is not its fault.
    private fun Throwable.asRejectedIfPermanent(videoId: String): Throwable =
        if (this is InnerTubeHttpException && (status == HttpStatusCode.BadRequest || status == HttpStatusCode.NotFound)) {
            SongRejectedException("song $videoId refused with ${status.value}")
        } else {
            this
        }

    private fun Throwable.asGoneIfMissing(playlistId: String): Throwable =
        if (this is InnerTubeHttpException && status == HttpStatusCode.NotFound) {
            PlaylistGoneException("playlist $playlistId not found")
        } else {
            this
        }

    private suspend inline fun <T> guarded(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private companion object {
        const val PRIVACY_UNLISTED = "UNLISTED"
        const val MAX_PAGES = 10
    }
}

internal fun SongItem.toMixTrack(): MixTrack =
    MixTrack(
        videoId = id,
        title = title,
        artist = artists.joinToString(", ") { it.name },
        thumbnailUrl = thumbnail.takeIf { it.isNotBlank() },
        durationMs = duration?.takeIf { it > 0 }?.let { it * 1000L },
    )

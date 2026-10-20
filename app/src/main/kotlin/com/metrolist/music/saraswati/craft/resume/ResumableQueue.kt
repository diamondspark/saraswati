/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 4 — a collection's own queue, started at the saved song and position when that song is
// still in the collection. A changed collection starts from the top; it is never guessed at.
package com.metrolist.music.saraswati.craft.resume

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeAlbumRadio
import com.metrolist.music.playback.queues.YouTubePlaylistQueue

class ResumableQueue(
    val sourceId: String,
    private val inner: Queue,
    private val resumeAt: ResumePoint?,
) : Queue {
    override val preloadItem: MediaMetadata?
        get() = inner.preloadItem

    override suspend fun getInitialStatus(): Queue.Status {
        val status = inner.getInitialStatus()
        val point = resumeAt ?: return status
        val index = status.items.indexOfFirst { it.mediaId == point.mediaId }
        return if (index < 0) {
            status.copy(mediaItemIndex = 0, position = 0L)
        } else {
            status.copy(mediaItemIndex = index, position = point.positionMs)
        }
    }

    override fun hasNextPage(): Boolean = inner.hasNextPage()

    override suspend fun nextPage() = inner.nextPage()
}

object ResumeLauncher {
    /**
     * The queue that plays [item] from its start, wrapped so it can resume at [point]. Songs and artists have no
     * collection to resume, so they get null. [localSongs] is set for a local playlist, whose songs come from the
     * database and never from the network.
     */
    fun queueFor(
        item: YTItem,
        localSongs: List<Song>?,
        point: ResumePoint?,
    ): Queue? {
        val inner: Queue =
            when (item) {
                is AlbumItem -> YouTubeAlbumRadio(item.playlistId)
                is PlaylistItem ->
                    if (localSongs != null) {
                        ListQueue(title = item.title, items = localSongs.map { it.toMediaItem() })
                    } else {
                        YouTubePlaylistQueue(item.id, item.title)
                    }
                is EpisodeItem -> ListQueue(items = listOf(item.toMediaMetadata().toMediaItem()))
                else -> return null
            }
        return ResumableQueue(item.id, inner, point)
    }
}

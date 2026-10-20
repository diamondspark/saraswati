/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 9 — a Station as a play queue that never leaves the car silent. Online it is the
// station's radio, untouched. Offline, or online when the radio cannot be loaded, it plays the songs saved for
// the station that can play with no network, shuffled, with no further pages. Nothing here makes a request of
// its own; an empty result is an honest "nothing to play" for the caller to show.
package com.metrolist.music.saraswati.offline

import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

class OfflineFirstStationQueue(
    private val title: String,
    private val online: Queue,
    private val offline: List<MediaMetadata>,
    private val isOnline: () -> Boolean,
    private val playable: OfflinePlayability,
    private val random: Random = Random.Default,
) : Queue {
    @Volatile private var playingOffline = false

    override val preloadItem: MediaMetadata? get() = online.preloadItem

    override suspend fun getInitialStatus(): Queue.Status {
        if (isOnline()) {
            val radio =
                try {
                    online.getInitialStatus()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            if (radio != null && radio.items.isNotEmpty()) {
                playingOffline = false
                return radio
            }
        }
        playingOffline = true
        val songs = offline.filter { playable.isPlayableOffline(it.id) }.shuffled(random)
        return Queue.Status(title, songs.map { it.toMediaItem() }, 0)
    }

    override fun hasNextPage(): Boolean = !playingOffline && online.hasNextPage()

    override suspend fun nextPage() = if (playingOffline) emptyList() else online.nextPage()
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.tuning

import androidx.media3.common.MediaItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.pages.NextResult
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue

sealed interface TuneResult {
    object Tuned : TuneResult

    object Busy : TuneResult

    object Failed : TuneResult

    object Unchanged : TuneResult
}

interface TunablePlayer {
    val currentIndex: Int
    val currentMediaId: String?

    fun replaceAfterCurrent(items: List<MediaItem>)

    fun adoptQueue(queue: Queue)
}

/**
 * Swaps the songs after the playing one for another radio. The playing song is never touched.
 * [api] is passed in so the chips the new radio offers can be shown; it defaults to the unconfirmed
 * api, which offers none.
 */
class RadioTuner(
    val state: RadioTuningState,
    private val next: suspend (WatchEndpoint) -> Result<NextResult>,
    private val player: TunablePlayer,
    private val snooze: suspend () -> Set<String>,
    private val clock: () -> Long,
    private val api: RadioTuningApi = UnconfirmedRadioTuningApi,
) {
    private var lastTapAt: Long? = null
    private var inFlight = false

    suspend fun select(chip: TuningChip): TuneResult {
        // One chip at a time: tapping the selected chip again clears the selection. No call is made.
        if (chip.selected) {
            state.publish(state.chips.value.map { it.copy(selected = false) })
            return TuneResult.Unchanged
        }
        if (inFlight) return TuneResult.Busy
        val now = clock()
        lastTapAt?.let { if (now - it < TAP_DEBOUNCE_MS) return TuneResult.Busy }
        lastTapAt = now

        inFlight = true
        try {
            val result = next(chip.endpoint).getOrNull() ?: return TuneResult.Failed
            val currentId = player.currentMediaId
            val snoozed = snooze()
            val items =
                result.items
                    .filter { it.id != currentId && it.id !in snoozed }
                    .map { it.toMediaItem() }
            if (items.isEmpty()) return TuneResult.Unchanged

            player.replaceAfterCurrent(items)
            // SARASWATI: Phase 6 fix (DEF-I3) — the tuned radio continues from its own first batch, so nothing repeats.
            player.adoptQueue(YouTubeQueue(result.endpoint, initialContinuation = result.continuation))
            state.publish(api.chips(result))
            return TuneResult.Tuned
        } finally {
            inFlight = false
        }
    }

    companion object {
        const val TAP_DEBOUNCE_MS = 1000L
    }
}

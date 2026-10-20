package com.metrolist.music.saraswati.craft.shuffle

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Owns the shuffle mode for the playing queue. Picks are fetched only when smart shuffle is
 * switched on, and at most once per [REFETCH_MIN_MS] for the same current song.
 */
class SmartShuffleController(
    private val player: ShufflePlayer,
    private val picks: suspend (currentId: String) -> Result<List<MediaItem>>,
    private val snooze: suspend () -> Set<String>,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) {
    private val _mode = MutableStateFlow(ShuffleMode.OFF)
    val mode: StateFlow<ShuffleMode> = _mode.asStateFlow()

    private var generation = 0
    private var lastFetch: Fetch? = null
    private val played = HashSet<String>()

    suspend fun setMode(target: ShuffleMode) {
        val gen = ++generation
        if (target == _mode.value) return
        when (target) {
            ShuffleMode.OFF -> {
                removeUnplayedPicks()
                player.setShuffle(false)
                _mode.value = ShuffleMode.OFF
            }
            ShuffleMode.SHUFFLE -> {
                removeUnplayedPicks()
                player.setShuffle(true)
                _mode.value = ShuffleMode.SHUFFLE
            }
            ShuffleMode.SMART -> enableSmart(gen)
        }
    }

    /** A new queue replaced the timeline: smart goes back to plain shuffle, or off. */
    fun onNewQueue(shuffleKept: Boolean) {
        generation++
        played.clear()
        _mode.value = if (shuffleKept) ShuffleMode.SHUFFLE else ShuffleMode.OFF
    }

    /** Keeps the mode honest when shuffle changes outside this controller, such as the car's toggle. */
    fun onPlayerShuffleChanged(enabled: Boolean) {
        val current = _mode.value
        if (enabled) {
            if (current == ShuffleMode.OFF) _mode.value = ShuffleMode.SHUFFLE
        } else if (current != ShuffleMode.OFF) {
            removeUnplayedPicks()
            _mode.value = ShuffleMode.OFF
        }
    }

    fun onCurrentItemChanged() {
        val index = player.currentIndex
        if (index in 0 until player.count && player.isPick(index)) {
            played += player.mediaIdAt(index)
        }
    }

    private suspend fun enableSmart(gen: Int) {
        val count = player.count
        if (count == 0 || SmartShuffle.pickCount(count) == 0) return fallbackShuffle()
        val currentId = player.mediaIdAt(player.currentIndex)
        if (currentId.startsWith(SmartShuffle.LOCAL_ID_PREFIX)) return fallbackShuffle()

        val candidates = candidatesFor(currentId).getOrNull() ?: return fallbackShuffle()
        if (gen != generation) return

        val base = player.count
        val queueIds = HashSet<String>(base)
        for (i in 0 until base) queueIds += player.mediaIdAt(i)
        val chosen = SmartShuffle.pick(candidates, queueIds, snooze(), SmartShuffle.pickCount(base))
        if (chosen.isEmpty()) return fallbackShuffle()

        // Nothing suspends from here to the mode change, so the mode is set before the
        // player's shuffle listener runs and that listener leaves the smart order alone.
        player.setShuffle(true)
        player.append(chosen)
        player.setOrder(SmartShuffle.order(player.currentIndex, base, base, chosen.size, random))
        _mode.value = ShuffleMode.SMART
    }

    private fun fallbackShuffle() {
        player.setShuffle(true)
        _mode.value = ShuffleMode.SHUFFLE
    }

    private suspend fun candidatesFor(currentId: String): Result<List<MediaItem>> {
        val now = clock()
        lastFetch?.let { last ->
            if (last.seedId == currentId && now - last.atMs < REFETCH_MIN_MS) return last.result
        }
        val result =
            try {
                picks(currentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        // Only a success is kept, so a failed fetch is retried the next time smart shuffle is turned on.
        if (result.isSuccess) lastFetch = Fetch(currentId, now, result)
        return result
    }

    private fun removeUnplayedPicks() {
        val current = player.currentIndex
        val doomed =
            (0 until player.count).filter {
                it != current && player.isPick(it) && player.mediaIdAt(it) !in played
            }
        if (doomed.isNotEmpty()) player.remove(doomed)
    }

    private class Fetch(
        val seedId: String,
        val atMs: Long,
        val result: Result<List<MediaItem>>,
    )

    companion object {
        const val REFETCH_MIN_MS = 60_000L
    }
}

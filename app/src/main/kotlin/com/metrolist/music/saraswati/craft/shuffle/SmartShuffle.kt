package com.metrolist.music.saraswati.craft.shuffle

import android.os.Bundle
import androidx.media3.common.MediaItem
import kotlin.math.min
import kotlin.random.Random

enum class ShuffleMode { OFF, SHUFFLE, SMART }

fun ShuffleMode.next(): ShuffleMode =
    when (this) {
        ShuffleMode.OFF -> ShuffleMode.SHUFFLE
        ShuffleMode.SHUFFLE -> ShuffleMode.SMART
        ShuffleMode.SMART -> ShuffleMode.OFF
    }

object SmartShuffle {
    const val EVERY = 3
    const val PICKS_MAX = 25
    const val EXTRA_SMART_PICK = "saraswati_smart_pick"
    const val LOCAL_ID_PREFIX = "local:"

    fun pickCount(baseCount: Int): Int = min(PICKS_MAX, (baseCount - 1) / EVERY).coerceAtLeast(0)

    fun pick(
        candidates: List<MediaItem>,
        queueIds: Set<String>,
        snoozed: Set<String>,
        count: Int,
    ): List<MediaItem> {
        if (count <= 0) return emptyList()
        val seen = HashSet<String>()
        val picked = ArrayList<MediaItem>(count)
        for (item in candidates) {
            if (picked.size == count) break
            val id = item.mediaId
            if (id.isEmpty() || id.startsWith(LOCAL_ID_PREFIX)) continue
            if (id in queueIds || id in snoozed) continue
            if (!seen.add(id)) continue
            picked += item.withSmartPickMark()
        }
        return picked
    }

    /**
     * Picks occupy indices `pickStart until pickStart + pickCount` and must follow the base
     * items (`pickStart == baseCount`), so the result is a permutation of the whole timeline.
     * The current item comes first; after every [EVERY] base items the next pick is placed.
     */
    fun order(
        currentIndex: Int,
        baseCount: Int,
        pickStart: Int,
        pickCount: Int,
        random: Random,
    ): IntArray {
        require(pickStart == baseCount) { "picks must follow the base items" }
        val total = baseCount + pickCount
        require(currentIndex in 0 until total) { "current index $currentIndex outside $total items" }

        val base = (0 until baseCount).filter { it != currentIndex }.shuffled(random)
        val picks = (pickStart until total).filter { it != currentIndex }
        val out = IntArray(total)
        var pos = 0
        var nextPick = 0
        out[pos++] = currentIndex
        base.forEachIndexed { i, index ->
            out[pos++] = index
            if ((i + 1) % EVERY == 0 && nextPick < picks.size) out[pos++] = picks[nextPick++]
        }
        while (nextPick < picks.size) out[pos++] = picks[nextPick++]
        return out
    }

    private fun MediaItem.withSmartPickMark(): MediaItem {
        val extras = Bundle().apply { mediaMetadata.extras?.let { putAll(it) } }
        extras.putBoolean(EXTRA_SMART_PICK, true)
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }
}

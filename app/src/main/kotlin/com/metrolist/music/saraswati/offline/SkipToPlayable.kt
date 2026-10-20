/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 7 — which item ahead of the current one can play with no network. The scan reads
// only the phone's own state through [playable], so choosing makes no request of any kind and skipping
// quickly through a tunnel cannot look like a burst to YouTube.
package com.metrolist.music.saraswati.offline

object SkipToPlayable {
    const val MAX_SCAN = 200

    /**
     * The first index in [order] (the play order after the current item) whose song [playable] accepts, or null
     * after looking at [MAX_SCAN] items or the whole list.
     */
    suspend fun nextPlayable(
        order: List<Int>,
        idAt: (Int) -> String,
        playable: suspend (String) -> Boolean,
    ): Int? {
        for (index in order.take(MAX_SCAN)) {
            if (playable(idAt(index))) return index
        }
        return null
    }

    /**
     * What follows [current] in [playOrder] (every queue index, in the order they play): the rest of the list,
     * then under repeat-all the items before it, once. Never [current] itself.
     */
    fun orderAfter(
        playOrder: List<Int>,
        current: Int,
        repeatAll: Boolean,
    ): List<Int> {
        val at = playOrder.indexOf(current)
        if (at < 0) return emptyList()
        val after = playOrder.subList(at + 1, playOrder.size)
        return if (repeatAll) after + playOrder.subList(0, at) else after.toList()
    }
}

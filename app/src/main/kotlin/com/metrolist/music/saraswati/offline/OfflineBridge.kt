/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 7 — when nothing ahead can play offline, up to ten of the phone's downloaded songs
// are put after the current one so the music goes on.
package com.metrolist.music.saraswati.offline

import kotlin.random.Random

object OfflineBridge {
    const val BRIDGE_SIZE = 10

    fun pick(
        pool: List<String>,
        exclude: Set<String>,
        random: Random,
    ): List<String> = pool.distinct().filter { it !in exclude }.shuffled(random).take(BRIDGE_SIZE)
}

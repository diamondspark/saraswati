/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — a join by code waits for the hub lookup, so a leave, a new join, a new room or a
// disconnect in that window must stop the join. Each action takes a new ticket; a join runs only while its ticket is
// still the latest, checked after the lookup and before the room is joined.
package com.metrolist.music.saraswati.family.sangat

import java.util.concurrent.atomic.AtomicLong

class JoinTickets {
    private val latest = AtomicLong(0L)

    /** A ticket for a join the person just asked for. It takes over from any join still waiting. */
    fun issue(): Long = latest.incrementAndGet()

    /** A leave, a new room or a disconnect: no join still waiting may go on. */
    fun cancel() {
        latest.incrementAndGet()
    }

    /** Runs [join] with the value of [resolve], but only if [ticket] is still the latest. Returns whether it ran. */
    suspend fun <T> runIfCurrent(
        ticket: Long,
        resolve: suspend () -> T,
        join: (T) -> Unit,
    ): Boolean {
        val value = resolve()
        if (latest.get() != ticket) return false
        join(value)
        return true
    }
}

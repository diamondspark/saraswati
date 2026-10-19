/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B1 — MainActivity asks the hub for this phone's role and
// for the station list on every RESUMED event, so a person who opens the app
// ten times in a minute sent twenty requests. Each of those calls is allowed
// once per [minIntervalMs]. The time of the last attempt lives in this object
// (a singleton's field), never in storage: the first call after the process
// starts always runs, and a phone that was asleep is never held back by a
// stored time. [clock] must keep counting while the device sleeps
// (SystemClock.elapsedRealtime), or a long sleep would look like a short gap.
package com.metrolist.music.saraswati.family

class ResumeThrottle(
    private val minIntervalMs: Long,
    private val clock: () -> Long,
) {
    private var lastAttemptAt: Long? = null

    /** True if the caller may go ahead; that also starts the next window. A refusal changes nothing. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        val last = lastAttemptAt
        if (last != null && now - last < minIntervalMs) return false
        lastAttemptAt = now
        return true
    }

    companion object {
        const val RESUME_INTERVAL_MS = 60_000L
    }
}

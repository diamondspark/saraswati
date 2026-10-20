/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — keeps the launch-time Parivaar work from hammering YouTube or the hub: after an
// attempt that did not finish, the next one waits [gapMs]. In memory only, so a new process always gets a try.
package com.metrolist.music.saraswati.family.parivaar

internal class AttemptBackoff(
    private val gapMs: Long,
) {
    @Volatile private var blockedUntilMs = Long.MIN_VALUE

    fun allowed(nowMs: Long): Boolean = nowMs >= blockedUntilMs

    fun failed(nowMs: Long) {
        blockedUntilMs = nowMs + gapMs
    }

    fun finished() {
        blockedUntilMs = Long.MIN_VALUE
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — the decisions behind the Sangat notification, kept free of Android so they can be
// unit tested. A room that was announced and not dismissed notifies; a tap on the notification joins only while
// the request is still fresh, so a stale tap cannot join a room the person has long forgotten about.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.LiveSangatRoom

object SangatNotifyPolicy {
    /** How long a notification tap stays good for a join. */
    const val JOIN_REQUEST_TTL_MS = 60_000L

    /** The rooms just announced that the person has not dismissed. A dismissed room never notifies again. */
    fun toNotify(
        announced: List<LiveSangatRoom>,
        dismissed: Set<String>,
    ): List<LiveSangatRoom> = announced.filter { it.roomCode !in dismissed }

    fun isFreshJoin(
        requestedAt: Long,
        now: Long,
    ): Boolean = now - requestedAt in 0..JOIN_REQUEST_TTL_MS
}

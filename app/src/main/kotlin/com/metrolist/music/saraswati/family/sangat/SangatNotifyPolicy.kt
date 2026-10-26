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

    /** The rooms just announced that the person has not dismissed. A dismissed room never notifies again, nor does the one this phone is in. */
    fun toNotify(
        announced: List<LiveSangatRoom>,
        dismissed: Set<String>,
        here: String? = null,
    ): List<LiveSangatRoom> = announced.filter { it.roomCode !in dismissed && it.roomCode != here }

    /** The posted notifications to take down: the room has ended, this phone is in it, or the person dismissed it. */
    fun toCancel(
        posted: Set<String>,
        live: List<LiveSangatRoom>,
        here: String?,
        dismissed: Set<String>,
    ): Set<String> {
        val liveCodes = live.mapTo(HashSet()) { it.roomCode }
        return posted.filterTo(HashSet()) { it !in liveCodes || it == here || it in dismissed }
    }

    fun isFreshJoin(
        requestedAt: Long,
        now: Long,
    ): Boolean = now - requestedAt in 0..JOIN_REQUEST_TTL_MS
}

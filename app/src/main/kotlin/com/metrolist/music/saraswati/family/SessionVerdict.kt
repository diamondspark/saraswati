/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B2 — when does a 401 mean "the owner revoked this phone"?
// Wave A forgot the session on any single 401. The hub answers one generic 401
// for every authentication failure, so a hub bug or a bad deploy would have
// unpaired every phone at once, with no way back but a new pairing link. Now a
// phone forgets its session only after three counted 401s in a row, each at
// least [MIN_GAP_MS] after the one before it. A 401 that comes sooner is not
// counted (the launch refresh and the stations refresh run together and would
// otherwise count one outage twice) and does not reset the run. Only a success
// (any 2xx) resets it, because only a success proves the session works; no signal
// or a 5xx says nothing either way and leaves the record as it was. The count
// and the time of the last counted 401 are plain DataStore values (not secrets),
// so they survive restarts.
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

object SessionVerdict {
    /** Counted 401s in a row after which the session is forgotten. */
    const val STRIKES_TO_CLEAR = 3

    /** A 401 counts only this long after the previous counted one. */
    const val MIN_GAP_MS = 10 * 60_000L
}

// One verdict at a time in this process: the role refresh and the stations refresh can answer together.
private val verdictLock = Mutex()

/**
 * Judges the hub's [answer] to a call made with [session]. A success resets the run of 401s. A 401 is
 * counted when it is the first or at least [SessionVerdict.MIN_GAP_MS] after the last counted one, and
 * the third counted one clears the stored session and device id (never the admin key or the Saral role cache).
 * A failure that is not a 401, and a storage error, change nothing. Returns true when the session was cleared. [nowMs] is wall-clock time: the record outlives a restart.
 */
internal suspend fun judgeSessionAnswer(
    family: FamilyStore,
    session: FamilySession,
    answer: Result<*>,
    nowMs: () -> Long,
): Boolean =
    verdictLock.withLock {
        try {
            val failure = answer.exceptionOrNull()
            when {
                failure == null -> {
                    family.resetUnauthorizedStrikes()
                    false
                }
                failure is HubException && failure.status == 401 -> countUnauthorized(family, session, nowMs())
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

private suspend fun countUnauthorized(
    family: FamilyStore,
    session: FamilySession,
    now: Long,
): Boolean {
    val strikes = family.unauthorizedStrikes()
    if (strikes.count > 0) {
        // A clock set back must not freeze the count until the old time comes round again: start the gap over.
        if (now < strikes.lastCountedAtMs) {
            family.saveUnauthorizedStrikes(strikes.copy(lastCountedAtMs = now))
            return false
        }
        if (now - strikes.lastCountedAtMs < SessionVerdict.MIN_GAP_MS) return false
    }
    val count = strikes.count + 1
    if (count >= SessionVerdict.STRIKES_TO_CLEAR) {
        family.clearSession(session.deviceId)
        return true
    }
    family.saveUnauthorizedStrikes(UnauthorizedStrikes(count, now))
    return false
}

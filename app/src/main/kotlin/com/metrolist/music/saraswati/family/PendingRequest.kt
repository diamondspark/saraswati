/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B1 — what a pending join request looks like on both
// phones. The claiming phone and the owner's phone each show the last four
// characters of the device id the hub issued, so the owner can ask the person
// to say theirs aloud before approving (the pairing link alone proves nothing
// about who holds the phone). The owner's phone also shows how old the request
// is and stops offering Approve once the hub would refuse it.
package com.metrolist.music.saraswati.family

/** The hub refuses to approve a request older than this (410). */
const val PENDING_APPROVAL_TTL_MS = 60 * 60 * 1000L

object ClaimCode {
    private const val LENGTH = 4

    /** The last four characters of [deviceId], in capitals. Shorter ids are shown whole. */
    fun of(deviceId: String): String = deviceId.takeLast(LENGTH).uppercase()
}

/** How long ago a request was made, in the unit the row shows. */
sealed interface RequestAge {
    data object JustNow : RequestAge

    data class Minutes(
        val count: Int,
    ) : RequestAge

    data class Hours(
        val count: Int,
    ) : RequestAge

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS

        /** A negative age (the two phones' clocks disagree) reads as just now. */
        fun of(ageMs: Long): RequestAge =
            when {
                ageMs < MINUTE_MS -> JustNow
                ageMs < HOUR_MS -> Minutes((ageMs / MINUTE_MS).toInt())
                else -> Hours((ageMs / HOUR_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
    }
}

/**
 * One pending row, ready to draw. [age] is null when the hub gave no claim time; such a request is not
 * marked expired here, since the hub is the judge (its 410 shows as [FamilyError.EXPIRED]).
 */
data class PendingRequestUi(
    val code: String,
    val age: RequestAge?,
    val expired: Boolean,
) {
    /** Approve is offered only while the hub would still accept it. Deny and Remove always stay. */
    val canApprove: Boolean get() = !expired
}

/** The hub's own rule: refused when more than an hour has passed since the claim. */
fun RosterEntry.pendingRequest(now: Long): PendingRequestUi {
    val claimed = claimedAt
    val ageMs = claimed?.let { now - it }
    return PendingRequestUi(
        code = ClaimCode.of(deviceId),
        age = ageMs?.let(RequestAge::of),
        expired = ageMs != null && ageMs > PENDING_APPROVAL_TTL_MS,
    )
}

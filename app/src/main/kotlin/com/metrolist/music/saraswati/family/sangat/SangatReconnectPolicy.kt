/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — when the Sangat socket may be opened again after it dropped.
//
// Never again: 401 (no or revoked session) and 503 (Sangat is off) on the upgrade, and the hub's own close
// codes 4001 (device revoked) and 4003 (Sangat disabled). A retry would only fail the same way, and a
// revoked phone should not keep knocking. Everything else is a dropped network or a busy hub and is retried,
// including 1001 (the hub closed an idle socket). A 429 on the upgrade is retried too, but no sooner than
// [minimumBackoffMs]: the hub is rate-limiting this phone and hammering it makes that worse.
package com.metrolist.music.saraswati.family.sangat

object SangatReconnectPolicy {
    const val CLOSE_DEVICE_REVOKED = 4001
    const val CLOSE_SANGAT_DISABLED = 4003

    private const val RATE_LIMITED_BACKOFF_MS = 60_000L

    /** [httpCode] is the answer to a refused upgrade, [closeCode] the code of a closed socket; null when there was none. */
    fun shouldReconnect(
        httpCode: Int?,
        closeCode: Int?,
    ): Boolean =
        when {
            httpCode == 401 || httpCode == 503 -> false
            closeCode == CLOSE_DEVICE_REVOKED || closeCode == CLOSE_SANGAT_DISABLED -> false
            else -> true
        }

    /** The shortest wait before the next attempt, whatever the ordinary backoff says. */
    fun minimumBackoffMs(httpCode: Int?): Long = if (httpCode == 429) RATE_LIMITED_BACKOFF_MS else 0L
}

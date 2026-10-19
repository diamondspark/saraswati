/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 review fix (I2, I3, M9) — the decisions behind opening and keeping the Sangat socket, as
// pure functions so a JUnit test (and a MockWebServer) can check what the Listen Together client does with
// them. The client keeps only the plumbing.
//
// Opening: no hub address, Sangat switched off, or no session token means no socket at all; otherwise the
// request goes to the hub address and nowhere else, carrying the device bearer.
//
// After a failure: 401 and close code 4001 mean this phone's session is dead, and 4003 means the hub closed
// Sangat down on purpose; those end the connection for good and the stored room goes with them. A 503 on the
// upgrade ends the retries too but may only be a hub having a bad moment, so the stored room is kept and the
// gate is asked again. Everything else is retried, a 429 no sooner than a minute.
package com.metrolist.music.saraswati.family.sangat

import okhttp3.OkHttpClient
import okhttp3.Request

object SangatConnect {
    sealed interface Plan {
        data class Refuse(
            val message: String,
        ) : Plan

        class Open(
            val url: String,
            val token: String,
        ) : Plan {
            override fun toString(): String = "Open(url=$url, token=<redacted>)"
        }
    }

    fun plan(
        url: String,
        sangatEnabled: Boolean,
        token: String?,
    ): Plan =
        when {
            url.isEmpty() -> Plan.Refuse("Sangat is not set up in this build.")
            !sangatEnabled -> Plan.Refuse("Sangat is switched off.")
            token.isNullOrEmpty() -> Plan.Refuse("Pair this phone with your family to use Sangat.")
            else -> Plan.Open(url, token)
        }

    /** Throws [IllegalArgumentException] for a URL or token OkHttp rejects; its message quotes the header, so never log it. */
    fun request(
        url: String,
        token: String,
        userAgent: String,
    ): Request =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Authorization", "Bearer $token")
            .build()

    /** A followed redirect would send the bearer wherever the redirect points. */
    fun hardened(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.followRedirects(false).followSslRedirects(false)

    sealed interface Failure {
        data class Retry(
            val minBackoffMs: Long,
        ) : Failure

        /** [wipeSession]: forget the stored room too. [refreshGate]: ask the hub for the kill-switches again. */
        data class Stop(
            val message: String,
            val wipeSession: Boolean,
            val refreshGate: Boolean,
        ) : Failure
    }

    fun afterFailure(
        httpCode: Int?,
        closeCode: Int?,
    ): Failure =
        when {
            httpCode == 401 || closeCode == SangatReconnectPolicy.CLOSE_DEVICE_REVOKED ->
                Failure.Stop("This phone is no longer signed in to the family hub.", wipeSession = true, refreshGate = false)
            closeCode == SangatReconnectPolicy.CLOSE_SANGAT_DISABLED ->
                Failure.Stop("Sangat is switched off.", wipeSession = true, refreshGate = true)
            httpCode == 503 ->
                Failure.Stop("Sangat is not available right now.", wipeSession = false, refreshGate = true)
            else -> Failure.Retry(SangatReconnectPolicy.minimumBackoffMs(httpCode))
        }
}

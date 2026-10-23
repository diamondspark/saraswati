/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the app-side half of the update pipeline: a thin,
// pure-logic wrapper around update-core's already-tested
// ManifestParser/UpdateDecider (Task 4) that the "Sudhaar" ("Update ready")
// Home banner calls. Two things matter enough to pin down with tests:
//
//  - Throttle: at most one network check per 12 hours, computed from a
//    caller-supplied `lastCheckMillis` rather than any internal mutable
//    state, so the same instance can be constructed fresh on every Home
//    screen composition while the actual "when did we last check" fact
//    lives in a DataStore preference the caller owns (HomeScreen.kt).
//  - Never throw: a manifest host hiccup, a malformed response, or any
//    other failure must resolve to [UpdateStatus.Failed], not an exception
//    bubbling into the Home screen's coroutine. CancellationException is
//    the one exception deliberately let through — swallowing it would
//    break structured concurrency (a cancelled Home screen must stay
//    cancelled).
package com.metrolist.music.saraswati.update

import dev.diamondspark.saraswati.updatecore.Decision
import dev.diamondspark.saraswati.updatecore.ManifestParser
import dev.diamondspark.saraswati.updatecore.MANIFEST_BASE_URL
import dev.diamondspark.saraswati.updatecore.UpdateDecider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import com.metrolist.innertube.saraswati.LocalIdEgressGuard

/** One GET. Implementations should return null rather than throw on failure; [UpdateChecker] tolerates either. */
fun interface HttpFetcher {
    suspend fun get(url: String): String?
}

/**
 * The real [HttpFetcher] the Home screen wires into [UpdateChecker]: a
 * plain HTTPS GET over the app's existing OkHttp-backed Ktor client,
 * swallowing any non-2xx response or I/O failure into null rather than
 * throwing — [UpdateChecker.check] is defensive either way, but there is
 * no reason to make it rely on that.
 */
object SaraswatiUpdateHttpFetcher : HttpFetcher {
    private val client by lazy { HttpClient(OkHttp) { install(LocalIdEgressGuard) } }

    override suspend fun get(url: String): String? = try {
        val response = client.get(url)
        response.bodyAsText().takeIf { response.status.value in 200..299 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

sealed interface UpdateStatus {
    data object None : UpdateStatus
    data class Ready(val versionName: String, val urgent: Boolean) : UpdateStatus
    data object Throttled : UpdateStatus
    data object Failed : UpdateStatus
}

private const val THROTTLE_MILLIS = 12 * 60 * 60 * 1000L

// SARASWATI: a failed check still spends a throttle slot, so an unreachable manifest host is not hit on
// every Home screen open, but only for this retry window instead of the full 12h. Otherwise a channel
// toggle made while offline would stay throttled for 12h after its forced check failed.
private const val FAILED_RETRY_MILLIS = 60 * 60 * 1000L

/** The value to persist as lastCheckMillis after a check returning [status] at [now]; null leaves the record untouched. */
fun lastCheckMillisAfter(status: UpdateStatus, now: Long): Long? = when (status) {
    UpdateStatus.Throttled -> null
    UpdateStatus.Failed -> now - THROTTLE_MILLIS + FAILED_RETRY_MILLIS
    else -> now
}

class UpdateChecker(
    private val http: HttpFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * [lastCheckMillis] is the caller's own record of the last time this
     * actually reached the network (not merely the last time this function
     * was called — a [UpdateStatus.Throttled] result must not update it).
     */
    suspend fun check(
        installedVersionCode: Long,
        channel: String,
        lastCheckMillis: Long,
    ): UpdateStatus {
        if (clock() - lastCheckMillis < THROTTLE_MILLIS) {
            return UpdateStatus.Throttled
        }
        return try {
            val body = http.get("$MANIFEST_BASE_URL$channel.json") ?: return UpdateStatus.Failed
            val manifest = ManifestParser.parse(body).getOrElse { return UpdateStatus.Failed }
            when (val decision = UpdateDecider.decide(installedVersionCode, manifest)) {
                is Decision.UpdateAvailable -> UpdateStatus.Ready(
                    versionName = decision.manifest.versionName,
                    urgent = decision.manifest.urgent,
                )
                is Decision.UpToDate, is Decision.AlreadyAhead, is Decision.Rejected -> UpdateStatus.None
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateStatus.Failed
        }
    }
}

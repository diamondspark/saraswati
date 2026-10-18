/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — UpdateChecker is the thin, testable seam between the
// "Sudhaar" ("Update ready") Home banner and update-core's pure
// ManifestParser/UpdateDecider. These tests pin down the two behaviors the
// banner actually depends on: the 12-hour network throttle (never on every
// Home screen open), and that a bad manifest or a dead network never throws
// out of check() — it always resolves to one of the four UpdateStatus
// outcomes. HttpFetcher is exercised through a real Ktor HttpClient backed
// by ktor-client-mock's MockEngine (already a test dependency, see
// app/build.gradle.kts), not a hand-rolled fake, so "never throws" is
// actually proven against an engine that can throw mid-request the way a
// real network failure would.
package com.metrolist.music.saraswati.update

import dev.diamondspark.saraswati.updatecore.RELEASE_URL_PREFIX
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val NOW = 1_700_000_000_000L
private const val TWELVE_HOURS_MILLIS = 12 * 60 * 60 * 1000L

private fun manifestJson(
    versionCode: Long,
    channel: String = "stable",
    urgent: Boolean = false,
): String =
    """
    {
      "schema": 1,
      "channel": "$channel",
      "versionCode": $versionCode,
      "versionName": "v$versionCode",
      "apk": {
        "url": "${RELEASE_URL_PREFIX}v$versionCode/saraswati.apk",
        "sha256": "${"a".repeat(64)}",
        "size": 1000000
      },
      "urgent": $urgent,
      "notes": "",
      "publishedAt": "2026-10-17T00:00:00Z"
    }
    """.trimIndent()

/** Records every request the checker made and lets each test script one canned response/failure per call. */
private class RecordingFetcher(private val respond: suspend (url: String) -> String?) : HttpFetcher {
    val requestedUrls = mutableListOf<String>()

    override suspend fun get(url: String): String? {
        requestedUrls.add(url)
        return respond(url)
    }
}

/** [HttpFetcher] backed by a real Ktor [HttpClient] whose engine is [MockEngine] — exercises the real request path. */
private fun mockEngineFetcher(handler: suspend (url: String) -> Pair<Int, String>?): HttpFetcher {
    val engine = MockEngine { request ->
        val result = handler(request.url.toString())
            ?: throw IOException("simulated network failure")
        val (status, body) = result
        respond(
            content = body,
            status = HttpStatusCode.fromValue(status),
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
    val client = HttpClient(engine)
    return HttpFetcher { url -> client.get(url).bodyAsText() }
}

class UpdateCheckerTest {

    @Test
    fun `newer manifest returns Ready`() = runBlocking {
        val fetcher = RecordingFetcher { manifestJson(versionCode = 200) }
        val checker = UpdateChecker(fetcher) { NOW }

        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = 0L)

        assertEquals(UpdateStatus.Ready(versionName = "v200", urgent = false), status)
    }

    @Test
    fun `urgent manifest returns Ready with urgent true`() = runBlocking {
        val fetcher = RecordingFetcher { manifestJson(versionCode = 200, urgent = true) }
        val checker = UpdateChecker(fetcher) { NOW }

        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = 0L)

        assertEquals(UpdateStatus.Ready(versionName = "v200", urgent = true), status)
    }

    @Test
    fun `same version returns None`() = runBlocking {
        val fetcher = RecordingFetcher { manifestJson(versionCode = 100) }
        val checker = UpdateChecker(fetcher) { NOW }

        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = 0L)

        assertEquals(UpdateStatus.None, status)
    }

    @Test
    fun `older manifest returns None`() = runBlocking {
        val fetcher = RecordingFetcher { manifestJson(versionCode = 50) }
        val checker = UpdateChecker(fetcher) { NOW }

        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = 0L)

        assertEquals(UpdateStatus.None, status)
    }

    @Test
    fun `second check inside 12 hours returns Throttled without a request`() = runBlocking {
        val fetcher = RecordingFetcher { manifestJson(versionCode = 200) }
        val checker = UpdateChecker(fetcher) { NOW }
        val sixHoursAgo = NOW - 6 * 60 * 60 * 1000L

        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = sixHoursAgo)

        assertEquals(UpdateStatus.Throttled, status)
        assertTrue("expected no network request, got ${fetcher.requestedUrls}", fetcher.requestedUrls.isEmpty())
    }

    @Test
    fun `check right at the 12 hour boundary makes a request`() = runBlocking {
        val fetcher = RecordingFetcher { manifestJson(versionCode = 200) }
        val checker = UpdateChecker(fetcher) { NOW }
        val exactlyTwelveHoursAgo = NOW - TWELVE_HOURS_MILLIS

        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = exactlyTwelveHoursAgo)

        assertEquals(UpdateStatus.Ready(versionName = "v200", urgent = false), status)
        assertEquals(1, fetcher.requestedUrls.size)
    }

    @Test
    fun `network failure returns Failed and never throws`() = runBlocking {
        val fetcher = mockEngineFetcher { null } // null -> handler throws inside the engine

        val checker = UpdateChecker(fetcher) { NOW }
        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = 0L)

        assertEquals(UpdateStatus.Failed, status)
    }

    @Test
    fun `malformed manifest returns Failed`() = runBlocking {
        val fetcher = RecordingFetcher { "{ this is not valid json" }
        val checker = UpdateChecker(fetcher) { NOW }

        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = 0L)

        assertEquals(UpdateStatus.Failed, status)
    }

    @Test
    fun `null response returns Failed`() = runBlocking {
        val fetcher = RecordingFetcher { null }
        val checker = UpdateChecker(fetcher) { NOW }

        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = 0L)

        assertEquals(UpdateStatus.Failed, status)
    }

    @Test
    fun `requests the manifest for the given channel`() = runBlocking {
        val fetcher = RecordingFetcher { manifestJson(versionCode = 200, channel = "beta") }
        val checker = UpdateChecker(fetcher) { NOW }

        checker.check(installedVersionCode = 100, channel = "beta", lastCheckMillis = 0L)

        assertEquals(listOf("https://diamondspark.github.io/saraswati/beta.json"), fetcher.requestedUrls)
    }

    @Test
    fun `real Ktor client round trips a successful response`() = runBlocking {
        val fetcher = mockEngineFetcher { url ->
            assertEquals("https://diamondspark.github.io/saraswati/stable.json", url)
            200 to manifestJson(versionCode = 200)
        }
        val checker = UpdateChecker(fetcher) { NOW }

        val status = checker.check(installedVersionCode = 100, channel = "stable", lastCheckMillis = 0L)

        assertEquals(UpdateStatus.Ready(versionName = "v200", urgent = false), status)
    }
}

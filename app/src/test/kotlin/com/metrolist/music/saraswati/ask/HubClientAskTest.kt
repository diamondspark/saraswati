/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — HubClient.ask against a local server: the request it sends, the daily-limit
// answer, and the re-check of a reply the app did not expect.
package com.metrolist.music.saraswati.family

import com.metrolist.music.saraswati.ask.AskIntent
import com.metrolist.music.saraswati.ask.AskLimitReached
import com.metrolist.music.saraswati.ask.AskReply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class HubClientAskTest {
    private lateinit var server: MockWebServer
    private lateinit var hub: HubClient
    private val question = "Bhajan sunao"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        hub = HubClient(server.url("/").toString(), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun reply(
        code: Int,
        body: String,
    ) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    private fun ask(text: String = question): Result<AskReply> = runBlocking { hub.ask("sess", text, "hi-IN") }

    @Test
    fun `ask posts question and locale with the bearer`() {
        reply(200, """{"intent":{"kind":"search","query":"bhajan"},"fallback":false}""")

        val result = ask()

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "no request reached the server" }
        assertEquals("POST", request.method)
        assertEquals("/ask", request.url.encodedPath)
        assertEquals("Bearer sess", request.headers["Authorization"])
        val sent = Json.parseToJsonElement(requireNotNull(request.body).utf8()).toString()
        assertTrue(sent, sent.contains("\"question\":\"Bhajan sunao\""))
        assertTrue(sent, sent.contains("\"locale\":\"hi-IN\""))
        assertEquals(AskReply(AskIntent.Search("bhajan"), fallback = false), result.getOrNull())
    }

    @Test
    fun `429 daily limit maps to AskLimitReached with resetAt`() {
        reply(429, """{"error":"daily limit","resetAt":"2026-10-20T00:00:00.000Z"}""")

        val error = ask().exceptionOrNull()

        assertTrue("$error", error is AskLimitReached)
        assertEquals(Instant.parse("2026-10-20T00:00:00Z"), (error as AskLimitReached).resetAt)
    }

    @Test
    fun `a burst 429 is an ordinary failure, not a daily limit`() {
        reply(429, """{"error":"too many requests"}""")

        val error = ask().exceptionOrNull()

        assertFalse("$error", error is AskLimitReached)
        assertEquals(429, (error as HubException).status)
    }

    @Test
    fun `an unknown kind from the hub becomes a search for the question`() {
        reply(200, """{"intent":{"kind":"download_everything","url":"https://example.invalid"},"fallback":false}""")

        assertEquals(AskReply(AskIntent.Search(question), fallback = true), ask().getOrNull())
    }

    @Test
    fun `an answer with a link or a bidi control is not shown, it becomes a search`() {
        reply(200, """{"intent":{"kind":"answer","text":"see https://example.invalid"},"fallback":false}""")
        reply(200, "{\"intent\":{\"kind\":\"answer\",\"text\":\"a\\u202eb\"},\"fallback\":false}")

        assertEquals(AskReply(AskIntent.Search(question), fallback = true), ask().getOrNull())
        assertEquals(AskReply(AskIntent.Search(question), fallback = true), ask().getOrNull())
    }

    @Test
    fun `a plain answer is kept as text`() {
        reply(200, """{"intent":{"kind":"answer","text":"Aaj Ganesh Chaturthi hai"},"fallback":false}""")

        assertEquals(AskReply(AskIntent.Answer("Aaj Ganesh Chaturthi hai"), fallback = false), ask().getOrNull())
    }

    @Test
    fun `IOException is a failure`() {
        val dead = HubClient("http://127.0.0.1:1/", OkHttpClient())

        val result = runBlocking { dead.ask("sess", question, "en") }

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is AskLimitReached)
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: feedback — HubClient's feedback calls against a local server: the request each one sends (method,
// path, query, bearer, JSON body), the answer it parses, and the awkward text that must arrive intact.
package com.metrolist.music.saraswati.family.feedback

import com.metrolist.music.saraswati.family.HubClient
import com.metrolist.music.saraswati.family.HubException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HubClientFeedbackTest {
    private lateinit var server: MockWebServer
    private lateinit var hub: HubClient
    private val diagnostics = FeedbackDiagnostics("13.7.0-s4119", "17 (API 37)", "Google Pixel 8 Pro")

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
        body: String = "",
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

    private fun takeRequest(): RecordedRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "no request reached the server" }

    private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(requireNotNull(body).utf8()) as JsonObject

    // --- submitFeedback ------------------------------------------------

    @Test
    fun `submit posts kind, text and diagnostics with the bearer and returns the id`() =
        runBlocking {
            reply(201, """{"id":"fb-1"}""")

            val result = hub.submitFeedback("sess-1", FeedbackKind.Bug, "The player stops", diagnostics)

            assertEquals("fb-1", result.getOrNull())
            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/feedback", request.url.encodedPath)
            assertNull(request.url.encodedQuery)
            assertEquals("Bearer sess-1", request.headers["Authorization"])
            assertEquals(
                buildJsonObject {
                    put("kind", "bug")
                    put("text", "The player stops")
                    put(
                        "diagnostics",
                        buildJsonObject {
                            put("appVersion", "13.7.0-s4119")
                            put("android", "17 (API 37)")
                            put("device", "Google Pixel 8 Pro")
                        },
                    )
                },
                request.bodyJson(),
            )
        }

    @Test
    fun `submit omits diagnostics when there are none and sends idea as idea`() =
        runBlocking {
            reply(201, """{"id":"fb-2"}""")

            assertEquals("fb-2", hub.submitFeedback("admin-key", FeedbackKind.Idea, "A sleep timer", null).getOrNull())

            val request = takeRequest()
            assertEquals("Bearer admin-key", request.headers["Authorization"])
            assertEquals(
                buildJsonObject {
                    put("kind", "idea")
                    put("text", "A sleep timer")
                },
                request.bodyJson(),
            )
        }

    @Test
    fun `submit sends Hindi, emoji, backticks, quotes and markup intact`() =
        runBlocking {
            val texts =
                listOf(
                    "गाना बंद हो जाता है",
                    "Loved it 🙏🎶 and 👨‍👩‍👧",
                    "مرحبا بالعالم",
                    "use ```kotlin\nval x = 1\n``` and `code`",
                    "she said \"hi\" and it's 'odd'; DROP TABLE feedback;--",
                    "@everyone </details><script>alert(1)</script> **bold** [x](http://y)",
                    "back\\slash and\ttab and line\nbreak",
                )
            for (text in texts) {
                reply(201, """{"id":"fb"}""")

                assertTrue(hub.submitFeedback("sess", FeedbackKind.Bug, text, diagnostics).isSuccess)

                assertEquals(text, takeRequest().bodyJson().getValue("text").jsonPrimitive.content)
            }
        }

    @Test
    fun `submit passes text of exactly 1000 and 1001 units through unchanged for the hub to judge`() =
        runBlocking {
            for (length in listOf(1000, 1001)) {
                reply(if (length == 1000) 201 else 400, if (length == 1000) """{"id":"fb"}""" else """{"error":"invalid"}""")

                val result = hub.submitFeedback("sess", FeedbackKind.Bug, "a".repeat(length), null)

                assertEquals(length, takeRequest().bodyJson().getValue("text").jsonPrimitive.content.length)
                assertEquals(length == 1000, result.isSuccess)
            }
        }

    @Test
    fun `submit treats a created answer with no id as a failure`() =
        runBlocking {
            for (body in listOf("""{}""", """{"id":""}""", "not json")) {
                reply(201, body)

                val failure = hub.submitFeedback("sess", FeedbackKind.Bug, "x", null).exceptionOrNull()

                assertTrue("body $body gave $failure", failure is HubException)
            }
        }

    @Test
    fun `submit failure never carries the text, the bearer or the response body`() =
        runBlocking {
            reply(503, """{"error":"feature disabled secret-body"}""")

            val failure = hub.submitFeedback("sess-token-9", FeedbackKind.Bug, "private words", diagnostics).exceptionOrNull()

            assertEquals(503, (failure as HubException).status)
            val shown = failure.toString() + failure.message
            assertFalse(shown.contains("private words"))
            assertFalse(shown.contains("sess-token-9"))
            assertFalse(shown.contains("secret-body"))
        }

    @Test
    fun `the wire request redacts the text from toString`() {
        val shown = FeedbackSubmitWire("bug", "private words", null).toString()

        assertFalse(shown.contains("private words"))
    }

    // --- listFeedback --------------------------------------------------

    @Test
    fun `list gets with the admin bearer, defaults to open and parses a full item`() =
        runBlocking {
            reply(
                200,
                """{"items":[{"id":"a1","kind":"bug","text":"It stops","status":"new","nickname":"Mummy",""" +
                    """"createdAt":1760000000000,"appVersion":"13.7.0-s4119","android":"17 (API 37)",""" +
                    """"device":"Google Pixel 8 Pro","issueUrl":"https://github.com/o/r/issues/3"}]}""",
            )

            val items = hub.listFeedback("admin-key").getOrNull()

            assertEquals(
                listOf(
                    FeedbackItem(
                        "a1",
                        FeedbackKind.Bug,
                        "It stops",
                        FeedbackStatus.New,
                        "Mummy",
                        1760000000000L,
                        diagnostics,
                        "https://github.com/o/r/issues/3",
                    ),
                ),
                items,
            )
            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/feedback", request.url.encodedPath)
            assertEquals("status=open&limit=100", request.url.encodedQuery)
            assertEquals("Bearer admin-key", request.headers["Authorization"])
            assertNull(request.body)
        }

    @Test
    fun `list sends the status filter it is given`() =
        runBlocking {
            reply(200, """{"items":[]}""")

            assertEquals(emptyList<FeedbackItem>(), hub.listFeedback("admin", FeedbackStatus.WontFix.wire).getOrNull())

            assertEquals("status=wontfix&limit=100", takeRequest().url.encodedQuery)
        }

    @Test
    fun `list parses an item with every optional field missing`() =
        runBlocking {
            reply(200, """{"items":[{"id":"a2","kind":"idea","text":"Dark mode","status":"planned","nickname":"Family member","createdAt":5}]}""")

            val item = hub.listFeedback("admin").getOrNull()?.single()

            assertEquals(FeedbackItem("a2", FeedbackKind.Idea, "Dark mode", FeedbackStatus.Planned, "Family member", 5L, null, null), item)
        }

    @Test
    fun `list keeps partial diagnostics instead of dropping them`() =
        runBlocking {
            reply(200, """{"items":[{"id":"a3","kind":"bug","text":"t","status":"seen","nickname":"n","createdAt":1,"device":"Pixel"}]}""")

            val item = hub.listFeedback("admin").getOrNull()?.single()

            assertEquals(FeedbackDiagnostics("", "", "Pixel"), item?.diagnostics)
        }

    @Test
    fun `list skips an item with an unknown kind or status and keeps the rest`() =
        runBlocking {
            reply(
                200,
                """{"items":[""" +
                    """{"id":"1","kind":"bug","text":"a","status":"new","nickname":"n","createdAt":1},""" +
                    """{"id":"2","kind":"praise","text":"b","status":"new","nickname":"n","createdAt":2},""" +
                    """{"id":"3","kind":"idea","text":"c","status":"archived","nickname":"n","createdAt":3},""" +
                    """{"id":"4","kind":"idea","text":"d","status":"done","nickname":"n","createdAt":"2026-10-20T00:00:00Z"},""" +
                    """{"id":"5","kind":"idea","text":"e"},""" +
                    """"not an object",null,""" +
                    """{"id":"6","kind":"idea","text":"f","status":"done","nickname":"n","createdAt":6,"extra":"ignored"}]}""",
            )

            val items = hub.listFeedback("admin").getOrNull()

            assertEquals(listOf("1", "6"), items?.map { it.id })
        }

    @Test
    fun `list round-trips Hindi, emoji and backticks in text`() =
        runBlocking {
            val text = "गाना 🙏 ```x``` @everyone </details>"
            reply(200, """{"items":[{"id":"u","kind":"bug","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)},"status":"new","nickname":"नानी","createdAt":1}]}""")

            val item = hub.listFeedback("admin").getOrNull()?.single()

            assertEquals(text, item?.text)
            assertEquals("नानी", item?.nickname)
        }

    @Test
    fun `list with a body that is not the envelope is a failure`() =
        runBlocking {
            for (body in listOf("""{"items":"x"}""", """[]""", "nope")) {
                reply(200, body)

                assertTrue("body $body", hub.listFeedback("admin").exceptionOrNull() is HubException)
            }
        }

    // --- setFeedbackStatus ---------------------------------------------

    @Test
    fun `setStatus puts the wire status with the admin bearer`() =
        runBlocking {
            for (status in FeedbackStatus.entries) {
                reply(200, """{"ok":true}""")

                assertTrue(hub.setFeedbackStatus("admin-key", "fb-1", status).isSuccess)

                val request = takeRequest()
                assertEquals("PUT", request.method)
                assertEquals("/feedback/fb-1", request.url.encodedPath)
                assertEquals("Bearer admin-key", request.headers["Authorization"])
                assertEquals(buildJsonObject { put("status", status.wire) }, request.bodyJson())
            }
        }

    @Test
    fun `setStatus percent-encodes the id as one path segment`() =
        runBlocking {
            reply(200, """{"ok":true}""")

            assertTrue(hub.setFeedbackStatus("admin", "a/b?x=1#f c%2e", FeedbackStatus.Done).isSuccess)

            val request = takeRequest()
            assertEquals("/feedback/a%2Fb%3Fx=1%23f%20c%252e", request.url.encodedPath)
            assertNull(request.url.encodedQuery)
        }

    @Test
    fun `setStatus refuses ids that would rewrite the path, without any request`() =
        runBlocking {
            for (bad in listOf("", ".", "..")) {
                val failure = hub.setFeedbackStatus("admin", bad, FeedbackStatus.Seen).exceptionOrNull()

                assertTrue("id '$bad' gave $failure", failure is IllegalArgumentException)
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `setStatus maps 404 for an unknown id to a HubException`() =
        runBlocking {
            reply(404, """{"error":"not found"}""")

            assertEquals(404, (hub.setFeedbackStatus("admin", "gone", FeedbackStatus.Seen).exceptionOrNull() as HubException).status)
        }
}

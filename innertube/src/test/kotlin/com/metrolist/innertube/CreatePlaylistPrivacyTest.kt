package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// SARASWATI: Phase 4 Task 12 — innertubex v0.8.4 sends "PRIVATE" for every new playlist; the Parivaar
// playlist has to be UNLISTED so the other phones can read it by link.
class CreatePlaylistPrivacyTest {
    private fun clientFor(engine: MockEngine) =
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }

    @Test
    fun `the overload sends the title and the privacy status it was given, signed in`() =
        runBlocking {
            val engine =
                MockEngine {
                    respond("""{"playlistId":"PLabcdefghij"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            clientFor(engine).use { client ->
                val innerTube = InnerTube(client).apply { cookie = "SAPISID=session; SID=x" }

                innerTube.createPlaylist(YouTubeClient.WEB_REMIX, "Saraswati · Mummy top songs", "UNLISTED")

                val request = engine.requestHistory.single()
                assertTrue(request.url.encodedPath.endsWith("playlist/create"))
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("Saraswati · Mummy top songs", body["title"]!!.jsonPrimitive.content)
                assertEquals("UNLISTED", body["privacyStatus"]!!.jsonPrimitive.content)
                assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("SAPISIDHASH "))
                assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("SAPISID=session"))
            }
        }

    // SARASWATI: Phase 4 review fix (4) — the same shape innertubex gives its own calls.
    @Test
    fun `the overload sends the client user agent, prettyPrint false and the PREF locale cookie to music youtube`() =
        runBlocking {
            val engine =
                MockEngine {
                    respond("""{"playlistId":"PLabcdefghij"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            clientFor(engine).use { client ->
                val innerTube = InnerTube(client).apply { cookie = "SAPISID=session; SID=x" }

                innerTube.createPlaylist(YouTubeClient.WEB_REMIX, "T", "UNLISTED")

                val request = engine.requestHistory.single()
                assertEquals(YouTubeClient.WEB_REMIX.userAgent, request.headers[HttpHeaders.UserAgent])
                assertEquals("false", request.url.parameters["prettyPrint"])
                val cookie = request.headers[HttpHeaders.Cookie].orEmpty()
                val locale = innerTube.locale
                assertTrue(cookie, cookie.contains("PREF=hl=${locale.hl}&gl=${locale.gl}"))
                assertTrue(cookie.contains("SAPISID=session"))
            }
        }

    @Test
    fun `the PREF cookie keeps its other fields and replaces hl and gl`() {
        assertEquals("a=b; PREF=hl=en&gl=IN", withPrefCookie("a=b", "en", "IN"))
        assertEquals("PREF=hl=en&gl=IN&tz=Asia.Kolkata; a=b", withPrefCookie("PREF=hl=fr&tz=Asia.Kolkata&gl=FR; a=b", "en", "IN"))
    }

    @Test
    fun `the old overload still sends PRIVATE`() =
        runBlocking {
            val engine =
                MockEngine {
                    respond("""{"playlistId":"PLabcdefghij"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            clientFor(engine).use { client ->
                InnerTube(client).apply { cookie = "SAPISID=session" }.createPlaylist(YouTubeClient.WEB_REMIX, "Plain")

                val body = Json.parseToJsonElement((engine.requestHistory.single().body as TextContent).text).jsonObject
                assertEquals("PRIVATE", body["privacyStatus"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun `a non-success answer throws instead of returning a playlist`() =
        runBlocking {
            val engine = MockEngine { respond("{}", HttpStatusCode.Forbidden) }
            clientFor(engine).use { client ->
                try {
                    InnerTube(client).apply { cookie = "SAPISID=session" }.createPlaylist(YouTubeClient.WEB_REMIX, "T", "UNLISTED")
                    fail("expected a failure")
                } catch (e: com.metrolist.innertubex.InnerTubeHttpException) {
                    assertEquals(HttpStatusCode.Forbidden, e.status)
                }
            }
        }
}

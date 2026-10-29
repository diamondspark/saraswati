/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — PUT and DELETE /push/token on HubClient, against a real local HTTP server: the method,
// the path, the device bearer and the JSON body, and a failure that carries the status.
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HubClientPushTest {
    private lateinit var server: MockWebServer
    private lateinit var hub: HubClient

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

    private fun reply(code: Int) {
        server.enqueue(MockResponse.Builder().code(code).build())
    }

    private fun takeRequest(): RecordedRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "no request reached the server" }

    @Test
    fun `registerPushToken puts the token with the session bearer and accepts 204`() =
        runBlocking {
            reply(204)

            val result = hub.registerPushToken("sess-1", "fcm:token_1")

            assertTrue(result.isSuccess)
            val request = takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/push/token", request.url.encodedPath)
            assertEquals("Bearer sess-1", request.headers["Authorization"])
            assertEquals(buildJsonObject { put("token", "fcm:token_1") }, Json.parseToJsonElement(requireNotNull(request.body).utf8()))
        }

    @Test
    fun `unregisterPushToken deletes with the session bearer and no body, and accepts 204`() =
        runBlocking {
            reply(204)

            val result = hub.unregisterPushToken("sess-1")

            assertTrue(result.isSuccess)
            val request = takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/push/token", request.url.encodedPath)
            assertEquals("Bearer sess-1", request.headers["Authorization"])
            assertEquals(0L, request.body?.size ?: 0L)
        }

    @Test
    fun `both calls turn a hub error into a failure carrying the status and not the token`() =
        runBlocking {
            for (status in listOf(401, 429, 500)) {
                reply(status)
                val put = hub.registerPushToken("sess-1", "secret-token").exceptionOrNull()
                assertEquals(status, (put as HubException).status)
                assertTrue("secret-token" !in put.message.orEmpty())
                takeRequest()

                reply(status)
                assertEquals(status, (hub.unregisterPushToken("sess-1").exceptionOrNull() as HubException).status)
                takeRequest()
            }
        }

    @Test
    fun `with no hub address both calls fail without a request`() =
        runBlocking {
            val unset = HubClient("", OkHttpClient())
            assertTrue(unset.registerPushToken("s", "t").isFailure)
            assertTrue(unset.unregisterPushToken("s").isFailure)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
}

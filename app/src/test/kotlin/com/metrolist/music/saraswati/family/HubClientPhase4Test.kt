/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 8 — the Sangat, Parivaar Mix and family-playlist calls on HubClient, against a
// real local HTTP server. Each function pins the request it sends (method, path, bearer, JSON body) and the
// shape it parses; every error status and a dead network are asserted for every function.
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import java.io.IOException
import java.util.concurrent.TimeUnit

class HubClientPhase4Test {
    private lateinit var server: MockWebServer
    private lateinit var hub: HubClient

    private val room =
        """{"roomCode":"ABCD1234","hostName":"Papa","hostDeviceId":"dev-1","carMode":true,"memberCount":3,"startedAt":"2026-10-19T10:00:00.000Z"}"""
    private val parivaar =
        """{"deviceId":"dev-2","memberName":"Asha","playlistId":"PLabcdefghij","updatedAt":"2026-10-19T10:00:00.000Z"}"""
    private val familyPlaylist =
        """{"id":"0b0f2a58-6d6a-4a4a-8f5d-1d9a8a0e5c11","playlistId":"PLabcdefghij","title":"Bhajans","addedByDeviceId":"dev-2","addedAt":"2026-10-19T10:00:00.000Z"}"""

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

    private fun RecordedRequest.json(): JsonElement = Json.parseToJsonElement(requireNotNull(body).utf8())

    private val allCalls: List<Pair<String, suspend () -> Result<*>>> =
        listOf(
            "getLiveSangat" to { hub.getLiveSangat("sess") },
            "setSangatCarMode" to { hub.setSangatCarMode("sess", "ABCD1234", true) },
            "putParivaarPlaylist" to { hub.putParivaarPlaylist("sess", "PLabcdefghij", "Asha") },
            "getParivaarPlaylists" to { hub.getParivaarPlaylists("sess") },
            "getFamilyPlaylists" to { hub.getFamilyPlaylists("sess") },
            "addFamilyPlaylist" to { hub.addFamilyPlaylist("sess", "PLabcdefghij", "Bhajans") },
            "removeFamilyPlaylist" to { hub.removeFamilyPlaylist("sess", "0b0f2a58-6d6a-4a4a-8f5d-1d9a8a0e5c11") },
        )

    @Test
    fun `getLiveSangat gets with the session bearer and parses the rooms`() =
        runBlocking {
            reply(200, """{"rooms":[$room]}""")

            val result = hub.getLiveSangat("sess")

            assertEquals(
                listOf(LiveSangatRoom("ABCD1234", "Papa", "dev-1", true, 3, "2026-10-19T10:00:00.000Z")),
                result.getOrNull(),
            )
            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/sangat/live", request.url.encodedPath)
            assertEquals("Bearer sess", request.headers["Authorization"])
        }

    @Test
    fun `getLiveSangat reads no rooms and ignores fields it does not know`() =
        runBlocking {
            reply(200, """{"rooms":[]}""")
            assertEquals(emptyList<LiveSangatRoom>(), hub.getLiveSangat("sess").getOrNull())

            reply(200, """{"rooms":[${room.dropLast(1)},"newField":1}],"extra":true}""")
            assertEquals(1, hub.getLiveSangat("sess").getOrNull()?.size)
        }

    @Test
    fun `getLiveSangat reads the song on now, and a null or missing one`() =
        runBlocking {
            reply(200, """{"rooms":[${room.dropLast(1)},"nowPlaying":{"title":"Pal Pal Dil Ke Paas","artist":"Kishore Kumar"}}]}""")
            assertEquals(
                LiveSangatSong("Pal Pal Dil Ke Paas", "Kishore Kumar"),
                hub.getLiveSangat("sess").getOrNull()?.single()?.nowPlaying,
            )

            reply(200, """{"rooms":[${room.dropLast(1)},"nowPlaying":null}]}""")
            assertEquals(null, hub.getLiveSangat("sess").getOrNull()?.single()?.nowPlaying)

            reply(200, """{"rooms":[$room]}""")
            assertEquals(null, hub.getLiveSangat("sess").getOrNull()?.single()?.nowPlaying)
        }

    @Test
    fun `setSangatCarMode puts the room code and flag with a content length`() =
        runBlocking {
            reply(200, room)

            val result = hub.setSangatCarMode("sess", "ABCD1234", true)

            assertTrue(result.isSuccess)
            val request = takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/sangat/car", request.url.encodedPath)
            assertEquals("Bearer sess", request.headers["Authorization"])
            assertEquals(
                buildJsonObject {
                    put("roomCode", "ABCD1234")
                    put("carMode", true)
                },
                request.json(),
            )
            assertEquals("a chunked body would be a 413 at the hub's front", request.body?.size?.toString(), request.headers["Content-Length"])
        }

    @Test
    fun `putParivaarPlaylist puts the playlist and name and parses the row`() =
        runBlocking {
            reply(200, parivaar)

            val result = hub.putParivaarPlaylist("sess", "PLabcdefghij", "Asha")

            assertEquals(ParivaarPlaylist("dev-2", "Asha", "PLabcdefghij", "2026-10-19T10:00:00.000Z"), result.getOrNull())
            val request = takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/parivaar/playlist", request.url.encodedPath)
            assertEquals("Bearer sess", request.headers["Authorization"])
            assertEquals(
                buildJsonObject {
                    put("playlistId", "PLabcdefghij")
                    put("memberName", "Asha")
                },
                request.json(),
            )
        }

    @Test
    fun `getParivaarPlaylists parses the list in the order the hub sent it`() =
        runBlocking {
            reply(200, """{"playlists":[$parivaar,${parivaar.replace("Asha", "Zed")}]}""")

            val result = hub.getParivaarPlaylists("sess")

            assertEquals(listOf("Asha", "Zed"), result.getOrNull()?.map { it.memberName })
            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/parivaar/playlists", request.url.encodedPath)
            assertEquals("Bearer sess", request.headers["Authorization"])
        }

    @Test
    fun `getFamilyPlaylists gets and parses the list`() =
        runBlocking {
            reply(200, """{"playlists":[$familyPlaylist]}""")

            val result = hub.getFamilyPlaylists("sess")

            assertEquals(
                listOf(FamilyPlaylist("0b0f2a58-6d6a-4a4a-8f5d-1d9a8a0e5c11", "PLabcdefghij", "Bhajans", "dev-2", "2026-10-19T10:00:00.000Z")),
                result.getOrNull(),
            )
            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/family-playlists", request.url.encodedPath)
        }

    @Test
    fun `addFamilyPlaylist posts and reads the 201 row`() =
        runBlocking {
            reply(201, familyPlaylist)

            val result = hub.addFamilyPlaylist("sess", "PLabcdefghij", "Bhajans")

            assertEquals("Bhajans", result.getOrNull()?.title)
            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/family-playlists", request.url.encodedPath)
            assertEquals("Bearer sess", request.headers["Authorization"])
            assertEquals(
                buildJsonObject {
                    put("playlistId", "PLabcdefghij")
                    put("title", "Bhajans")
                },
                request.json(),
            )
        }

    @Test
    fun `removeFamilyPlaylist deletes by id with the bearer and reads the 204`() =
        runBlocking {
            reply(204)

            val result = hub.removeFamilyPlaylist("admin-key", "0b0f2a58-6d6a-4a4a-8f5d-1d9a8a0e5c11")

            assertTrue(result.isSuccess)
            val request = takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/family-playlists/0b0f2a58-6d6a-4a4a-8f5d-1d9a8a0e5c11", request.url.encodedPath)
            assertEquals("Bearer admin-key", request.headers["Authorization"])
        }

    @Test
    fun `removeFamilyPlaylist refuses an id that could change the path, without a request`() =
        runBlocking {
            for (bad in listOf("", "..", "a/b", "a?b", "a b", "../roster")) {
                val failure = hub.removeFamilyPlaylist("sess", bad).exceptionOrNull()
                assertTrue("'$bad' should be refused, got $failure", failure is IllegalArgumentException)
            }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }

    @Test
    fun `every function turns 401 403 404 409 429 and 503 into a failure carrying the status`() =
        runBlocking {
            for (status in listOf(401, 403, 404, 409, 429, 503)) {
                for ((name, call) in allCalls) {
                    reply(status, """{"error":"nope"}""")

                    val failure = call().exceptionOrNull()

                    assertTrue("$name at $status should be a HubException, got $failure", failure is HubException)
                    assertEquals("$name at $status", status, (failure as HubException).status)
                    takeRequest()
                }
            }
        }

    @Test
    fun `a 2xx body that cannot be read is a failure, not a throw`() =
        runBlocking {
            for ((name, call) in allCalls.take(6)) {
                if (name == "setSangatCarMode") continue
                reply(200, "<html>captive portal</html>")

                assertTrue("$name should fail on a bad body", call().isFailure)
                takeRequest()
            }
        }

    @Test
    fun `every function turns a network IOException into a failure instead of throwing`() =
        runBlocking {
            server.close()

            for ((name, call) in allCalls) {
                val failure = call().exceptionOrNull()

                assertTrue("$name should surface the IOException, got $failure", failure is IOException)
            }
        }

    @Test
    fun `no failure prints the bearer`() =
        runBlocking {
            for ((name, call) in allCalls) {
                reply(401, """{"error":"sess"}""")

                val failure = call().exceptionOrNull()

                assertTrue("$name leaked the bearer: $failure", failure?.message?.contains("sess") != true)
                takeRequest()
            }
        }

    @Test
    fun `sangatWebSocketUrl turns https x workers dev into wss x workers dev ws`() {
        assertEquals("wss://x.workers.dev/ws", HubClient("https://x.workers.dev", OkHttpClient()).sangatWebSocketUrl())
        assertEquals("wss://x.workers.dev/ws", HubClient("https://x.workers.dev/", OkHttpClient()).sangatWebSocketUrl())
        assertEquals("wss://x.workers.dev/ws", HubClient("  https://x.workers.dev?a=1#b  ", OkHttpClient()).sangatWebSocketUrl())
    }

    @Test
    fun `sangatWebSocketUrl gives nothing for a hub that is not https or not set`() {
        assertEquals("", HubClient("http://x.workers.dev", OkHttpClient()).sangatWebSocketUrl())
        assertEquals("", HubClient("", OkHttpClient()).sangatWebSocketUrl())
        assertEquals("", HubClient("not a url", OkHttpClient()).sangatWebSocketUrl())
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 6 — HubClient against a real local HTTP server
// (MockWebServer). Every function gets a success test pinning the request it
// sends (method, path, bearer or deliberately no bearer, JSON body) and the
// response shape it parses; the 401/404/410/429 failure mapping and the
// network-failure mapping are asserted for every function, so a function
// added later without them is the one that stands out.
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class HubClientTest {
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

    private fun <T> Result<T>.hubException(): HubException {
        val failure = exceptionOrNull()
        assertTrue("expected a HubException failure but got $this", failure is HubException)
        return failure as HubException
    }

    /** Every call in [HubClient], with throwaway arguments, for the tests that must cover all of them. */
    private val allCalls: List<Pair<String, suspend () -> Result<*>>> =
        listOf(
            call("createPairing") { hub.createPairing("admin") },
            call("claimPairing") { hub.claimPairing("tok", "Mummy's Pixel") },
            call("pairingStatus") { hub.pairingStatus("dev-1", "secret") },
            call("getRoster") { hub.getRoster("admin") },
            call("approveDevice") { hub.approveDevice("admin", "dev-1") },
            call("revokeDevice") { hub.revokeDevice("admin", "dev-1") },
            call("getStations") { hub.getStations("admin") },
            call("setStations") { hub.setStations("admin", listOf(Station("s1", "Bhajans", "vid1", 2))) },
            call("getKillSwitches") { hub.getKillSwitches("admin") },
            call("setKillSwitches") { hub.setKillSwitches("admin", mapOf("sangat" to false)) },
            call("reportHealth") { hub.reportHealth("sess", "dev-1", 160, "13.7.0-s160", "Asia/Kolkata") },
            call("getHealth") { hub.getHealth("admin") },
        )

    private fun call(
        name: String,
        block: suspend () -> Result<*>,
    ): Pair<String, suspend () -> Result<*>> = name to block

    // --- createPairing -------------------------------------------------

    @Test
    fun `createPairing posts with the admin bearer and parses token and ISO expiry`() =
        runBlocking {
            reply(200, """{"token":"abc123","expiresAt":"2026-10-19T12:15:00.000Z"}""")

            val result = hub.createPairing("admin-key")

            assertEquals(PairingToken("abc123", "2026-10-19T12:15:00.000Z"), result.getOrNull())
            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/pairing/create", request.url.encodedPath)
            assertEquals("Bearer admin-key", request.headers["Authorization"])
        }

    // --- claimPairing --------------------------------------------------

    @Test
    fun `claimPairing posts token and device name with no bearer and parses the claim secret`() =
        runBlocking {
            val claimSecret = "ab".repeat(32)
            reply(200, """{"deviceId":"dev-1","status":"pending","claimSecret":"$claimSecret"}""")

            val result = hub.claimPairing("tok-1", "Mummy's Pixel")

            assertEquals(ClaimResult("dev-1", "pending", claimSecret), result.getOrNull())
            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/pairing/claim", request.url.encodedPath)
            assertNull("a claim is made before the phone owns any credential", request.headers["Authorization"])
            assertEquals(
                buildJsonObject {
                    put("token", "tok-1")
                    put("deviceName", "Mummy's Pixel")
                },
                request.json(),
            )
        }

    // --- pairingStatus -------------------------------------------------

    @Test
    fun `pairingStatus posts device id and claim secret with no bearer and parses pending`() =
        runBlocking {
            reply(200, """{"status":"pending"}""")

            val result = hub.pairingStatus("dev-1", "the-claim-secret")

            assertEquals(PairingStatus.Pending, result.getOrNull())
            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/pairing/status", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
            assertEquals(
                buildJsonObject {
                    put("deviceId", "dev-1")
                    put("claimSecret", "the-claim-secret")
                },
                request.json(),
            )
        }

    @Test
    fun `pairingStatus parses approved with the session token`() =
        runBlocking {
            reply(200, """{"status":"approved","sessionToken":"sess-xyz"}""")

            assertEquals(PairingStatus.Approved("sess-xyz"), hub.pairingStatus("dev-1", "s").getOrNull())
        }

    @Test
    fun `pairingStatus treats approved without a session token as a failure`() =
        runBlocking {
            reply(200, """{"status":"approved"}""")

            val result = hub.pairingStatus("dev-1", "s")

            assertTrue(result.isFailure)
            assertEquals(200, result.hubException().status)
        }

    @Test
    fun `pairingStatus treats an unknown status value as a failure`() =
        runBlocking {
            reply(200, """{"status":"banana"}""")

            assertTrue(hub.pairingStatus("dev-1", "s").isFailure)
        }

    // --- getRoster -----------------------------------------------------

    @Test
    fun `getRoster gets with the admin bearer and parses nullable name and approval time`() =
        runBlocking {
            reply(
                200,
                """
                {"devices":[
                  {"deviceId":"a","deviceName":"Dad's phone","status":"approved","approvedAt":1760000000000},
                  {"deviceId":"b","deviceName":null,"status":"pending","approvedAt":null}
                ]}
                """.trimIndent(),
            )

            val result = hub.getRoster("admin-key")

            assertEquals(
                listOf(
                    RosterEntry("a", "Dad's phone", "approved", 1_760_000_000_000L),
                    RosterEntry("b", null, "pending", null),
                ),
                result.getOrNull(),
            )
            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/roster", request.url.encodedPath)
            assertEquals("Bearer admin-key", request.headers["Authorization"])
        }

    // --- approveDevice -------------------------------------------------

    @Test
    fun `approveDevice posts to the device's approve path and parses the session token`() =
        runBlocking {
            reply(200, """{"deviceId":"dev-1","sessionToken":"sess-1","status":"approved"}""")

            val result = hub.approveDevice("admin-key", "dev-1")

            assertEquals(ApprovedDevice("dev-1", "sess-1", "approved"), result.getOrNull())
            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/roster/dev-1/approve", request.url.encodedPath)
            assertEquals("Bearer admin-key", request.headers["Authorization"])
        }

    // --- revokeDevice --------------------------------------------------

    @Test
    fun `revokeDevice posts to the device's revoke path and returns Unit`() =
        runBlocking {
            reply(200, """{"deviceId":"dev-1","status":"revoked"}""")

            val result = hub.revokeDevice("admin-key", "dev-1")

            assertTrue(result.isSuccess)
            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/roster/dev-1/revoke", request.url.encodedPath)
            assertEquals("Bearer admin-key", request.headers["Authorization"])
        }

    // --- getStations / setStations -------------------------------------

    @Test
    fun `getStations gets with whichever bearer it is given and parses the list`() =
        runBlocking {
            reply(200, """{"stations":[{"id":"s1","title":"Bhajans","seedVideoId":"vid1","iconIndex":2}]}""")

            val result = hub.getStations("a-session-token")

            assertEquals(listOf(Station("s1", "Bhajans", "vid1", 2)), result.getOrNull())
            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/stations", request.url.encodedPath)
            assertEquals("Bearer a-session-token", request.headers["Authorization"])
        }

    @Test
    fun `getStations parses an empty list`() =
        runBlocking {
            reply(200, """{"stations":[]}""")

            assertEquals(emptyList<Station>(), hub.getStations("admin").getOrNull())
        }

    @Test
    fun `setStations puts the whole list and parses the stored list`() =
        runBlocking {
            val stations = listOf(Station("s1", "Bhajans", "vid1", 2), Station("s2", "Ghazals", "vid2", 0))
            reply(
                200,
                """{"stations":[
                  {"id":"s1","title":"Bhajans","seedVideoId":"vid1","iconIndex":2},
                  {"id":"s2","title":"Ghazals","seedVideoId":"vid2","iconIndex":0}]}""",
            )

            val result = hub.setStations("admin-key", stations)

            assertEquals(stations, result.getOrNull())
            val request = takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/stations", request.url.encodedPath)
            assertEquals("Bearer admin-key", request.headers["Authorization"])
            assertEquals(
                buildJsonObject {
                    put(
                        "stations",
                        buildJsonArray {
                            for (station in stations) {
                                add(
                                    buildJsonObject {
                                        put("id", station.id)
                                        put("title", station.title)
                                        put("seedVideoId", station.seedVideoId)
                                        put("iconIndex", station.iconIndex)
                                    },
                                )
                            }
                        },
                    )
                },
                request.json(),
            )
        }

    // --- getKillSwitches / setKillSwitches -----------------------------

    @Test
    fun `getKillSwitches gets with the given bearer and parses the flat map`() =
        runBlocking {
            reply(200, """{"sangat":true,"parivaar_mix":false,"family_playlists":true,"cast_relay":true}""")

            val result = hub.getKillSwitches("a-session-token")

            assertEquals(
                mapOf(
                    "sangat" to true,
                    "parivaar_mix" to false,
                    "family_playlists" to true,
                    "cast_relay" to true,
                ),
                result.getOrNull(),
            )
            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/killswitches", request.url.encodedPath)
            assertEquals("Bearer a-session-token", request.headers["Authorization"])
        }

    @Test
    fun `setKillSwitches puts just the changed keys and parses the full map back`() =
        runBlocking {
            reply(200, """{"sangat":false,"parivaar_mix":true,"family_playlists":true,"cast_relay":true}""")

            val result = hub.setKillSwitches("admin-key", mapOf("sangat" to false))

            assertEquals(false, result.getOrNull()?.get("sangat"))
            assertEquals(4, result.getOrNull()?.size)
            val request = takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/killswitches", request.url.encodedPath)
            assertEquals("Bearer admin-key", request.headers["Authorization"])
            assertEquals(buildJsonObject { put("sangat", false) }, request.json())
        }

    // --- reportHealth --------------------------------------------------

    @Test
    fun `reportHealth posts the device's own report with its session bearer and accepts 204`() =
        runBlocking {
            reply(204)

            val result = hub.reportHealth("sess-1", "dev-1", 160, "13.7.0-s160", "Asia/Kolkata")

            assertTrue(result.isSuccess)
            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/health", request.url.encodedPath)
            assertEquals("Bearer sess-1", request.headers["Authorization"])
            assertEquals(
                buildJsonObject {
                    put("deviceId", "dev-1")
                    put("versionCode", 160)
                    put("versionName", "13.7.0-s160")
                    put("timezone", "Asia/Kolkata")
                },
                request.json(),
            )
        }

    @Test
    fun `reportHealth defaults the timezone to this device's own`() =
        runBlocking {
            reply(204)

            hub.reportHealth("sess-1", "dev-1", 160, "13.7.0-s160")

            val body = takeRequest().json() as JsonObject
            assertEquals(TimeZone.getDefault().id, body["timezone"]?.jsonPrimitive?.content)
        }

    // --- getHealth -----------------------------------------------------

    @Test
    fun `getHealth gets with the admin bearer and parses timezone and last-seen millis`() =
        runBlocking {
            reply(
                200,
                """
                {"devices":[
                  {"deviceId":"a","deviceName":"Mummy's Pixel","versionCode":160,"versionName":"13.7.0-s160","timezone":"Asia/Kolkata","lastSeenAt":1760000000000},
                  {"deviceId":"b","deviceName":null,"versionCode":159,"versionName":"13.6.0-s159","timezone":"America/Toronto","lastSeenAt":1759999999000}
                ]}
                """.trimIndent(),
            )

            val result = hub.getHealth("admin-key")

            assertEquals(
                listOf(
                    DeviceHealth("a", "Mummy's Pixel", 160, "13.7.0-s160", "Asia/Kolkata", 1_760_000_000_000L),
                    DeviceHealth("b", null, 159, "13.6.0-s159", "America/Toronto", 1_759_999_999_000L),
                ),
                result.getOrNull(),
            )
            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/health", request.url.encodedPath)
            assertEquals("Bearer admin-key", request.headers["Authorization"])
        }

    // --- failure mapping -----------------------------------------------

    @Test
    fun `every function maps 401, 404, 410 and 429 to a HubException carrying that status`() =
        runBlocking {
            for ((name, call) in allCalls) {
                for (code in listOf(401, 404, 410, 429)) {
                    reply(code, """{"error":"nope"}""")

                    val result = call()

                    assertTrue("$name should fail on HTTP $code", result.isFailure)
                    assertEquals("$name on HTTP $code", code, result.hubException().status)
                }
            }
        }

    @Test
    fun `a server error maps to a HubException too`() =
        runBlocking {
            reply(500, "oops")

            assertEquals(500, hub.getRoster("admin").hubException().status)
        }

    @Test
    fun `a failure never carries the bearer or the response body in its message`() =
        runBlocking {
            reply(401, """{"error":"bad token admin-key-123 echoed"}""")

            val failure = hub.getRoster("admin-key-123").hubException()

            assertFalse(failure.message.orEmpty().contains("admin-key-123"))
            assertFalse(failure.toString().contains("admin-key-123"))
        }

    @Test
    fun `a 2xx body of the wrong shape is a failure, not a thrown exception`() =
        runBlocking {
            reply(200, """{"unexpected":true}""")

            val result = hub.createPairing("admin")

            assertEquals(200, result.hubException().status)
        }

    @Test
    fun `an unusable 2xx body never leaks into the failure or its cause chain`() =
        runBlocking {
            // kotlinx.serialization quotes the offending input in its own messages.
            reply(200, """{"status":"approved","sessionToken":12345,"leak":"sess-LEAKED-value"}""")

            val failure = hub.pairingStatus("dev-1", "claim-secret-value").hubException()

            assertNull("a parser exception must not be chained on", failure.cause)
            assertFalse(failure.toString().contains("sess-LEAKED-value"))
            assertFalse(failure.toString().contains("claim-secret-value"))
        }

    @Test
    fun `types that hold a credential redact it in toString`() {
        val secret = "super-secret-value"

        assertFalse(ClaimResult("d", "pending", secret).toString().contains(secret))
        assertFalse(ApprovedDevice("d", secret, "approved").toString().contains(secret))
        assertFalse(PairingStatus.Approved(secret).toString().contains(secret))
    }

    @Test
    fun `a 2xx body that is not JSON at all is a failure, not a thrown exception`() =
        runBlocking {
            reply(200, "<html>captive portal</html>")

            assertTrue(hub.getStations("admin").isFailure)
        }

    // --- network failure -----------------------------------------------

    @Test
    fun `every function turns a network IOException into a failure instead of throwing`() =
        runBlocking {
            server.close()

            for ((name, call) in allCalls) {
                val result = call()

                assertTrue("$name should fail when the server is unreachable", result.isFailure)
                assertTrue(
                    "$name should surface the IOException, got ${result.exceptionOrNull()}",
                    result.exceptionOrNull() is IOException,
                )
            }
        }

    @Test
    fun `a connection dropped mid-response is a failure too`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"devices":[]}""")
                    .onResponseStart(SocketEffect.CloseSocket())
                    .build(),
            )

            val result = hub.getRoster("admin")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IOException)
        }

    // --- robustness ----------------------------------------------------

    @Test
    fun `a redirect is never followed because it would carry the bearer elsewhere`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", server.url("/elsewhere").toString())
                    .build(),
            )
            reply(200, """{"devices":[]}""")

            val result = hub.getRoster("admin-key")

            assertEquals(302, result.hubException().status)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a device id that could escape its path segment is refused without any request`() =
        runBlocking {
            for (badId in listOf("..", ".", "../stations", "a/b", "", " ", "a b", "dev-1?x=1", "dev-1#frag")) {
                assertTrue("approve '$badId'", hub.approveDevice("admin", badId).isFailure)
                assertTrue("revoke '$badId'", hub.revokeDevice("admin", badId).isFailure)
            }

            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a base URL without a trailing slash works`() =
        runBlocking {
            val bare = HubClient(server.url("/").toString().trimEnd('/'), OkHttpClient())
            reply(200, """{"devices":[]}""")

            assertEquals(emptyList<RosterEntry>(), bare.getRoster("admin").getOrNull())
            assertEquals("/roster", takeRequest().url.encodedPath)
        }

    @Test
    fun `a blank or malformed base URL fails every call instead of throwing`() =
        runBlocking {
            for (base in listOf("", "   ", "not a url", "ftp://example.com")) {
                val unconfigured = HubClient(base, OkHttpClient())

                val result = unconfigured.getRoster("admin")

                assertTrue("base '$base'", result.isFailure)
                assertNotNull(result.exceptionOrNull())
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `cancelling the caller cancels the call instead of turning into a failure result`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"devices":[]}""")
                    .headersDelay(10, TimeUnit.SECONDS)
                    .build(),
            )

            val started = System.nanoTime()
            val result = withTimeoutOrNull(300) { hub.getRoster("admin") }

            assertNull("the timeout must cancel the call, not be swallowed into a Result", result)
            assertTrue(
                "cancellation should be prompt, not wait out the 10s server delay",
                System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5),
            )
        }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 review fix (I2, I3, M9) — what the Sangat client decides before and after a socket, and what
// OkHttp really does with it against a server: the bearer goes to the hub URL and nowhere else, a redirect is not
// followed, and a refused upgrade is read back as the code the retry decision is made on.
package com.metrolist.music.saraswati.family.sangat

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SangatConnectTest {
    private lateinit var hub: MockWebServer
    private lateinit var elsewhere: MockWebServer
    private val client = SangatConnect.hardened(OkHttpClient.Builder()).build()

    @Before
    fun setUp() {
        hub = MockWebServer().apply { start() }
        elsewhere = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        hub.close()
        elsewhere.close()
    }

    @Test
    fun `no hub address, no switch or no token means no connection`() {
        assertTrue(SangatConnect.plan("", true, "s3cr3t") is SangatConnect.Plan.Refuse)
        assertTrue(SangatConnect.plan("wss://hub/ws", false, "s3cr3t") is SangatConnect.Plan.Refuse)
        assertTrue(SangatConnect.plan("wss://hub/ws", true, null) is SangatConnect.Plan.Refuse)
        assertTrue(SangatConnect.plan("wss://hub/ws", true, "") is SangatConnect.Plan.Refuse)
        val open = SangatConnect.plan("wss://hub/ws", true, "s3cr3t") as SangatConnect.Plan.Open
        assertEquals("wss://hub/ws", open.url)
        assertEquals("s3cr3t", open.token)
        assertFalse("a log line cannot print the token", open.toString().contains("s3cr3t"))
    }

    @Test
    fun `a 401 or a revoked device ends it and forgets the room, a 4003 too and asks the gate`() {
        val unauthorised = SangatConnect.afterFailure(401, null) as SangatConnect.Failure.Stop
        assertTrue(unauthorised.wipeSession)
        assertFalse(unauthorised.refreshGate)
        assertTrue((SangatConnect.afterFailure(null, 4001) as SangatConnect.Failure.Stop).wipeSession)
        val disabled = SangatConnect.afterFailure(null, 4003) as SangatConnect.Failure.Stop
        assertTrue(disabled.wipeSession)
        assertTrue(disabled.refreshGate)
    }

    @Test
    fun `a 503 stops the retries but keeps the stored room and asks the gate`() {
        val stop = SangatConnect.afterFailure(503, null) as SangatConnect.Failure.Stop
        assertFalse("a hub having a bad moment must not cost the family their room", stop.wipeSession)
        assertTrue(stop.refreshGate)
        assertEquals("Sangat is not available right now.", stop.message)
    }

    @Test
    fun `a dropped network retries at once and a 429 waits at least a minute`() {
        assertEquals(SangatConnect.Failure.Retry(0L), SangatConnect.afterFailure(null, null))
        assertEquals(SangatConnect.Failure.Retry(0L), SangatConnect.afterFailure(null, 1001))
        assertEquals(SangatConnect.Failure.Retry(0L), SangatConnect.afterFailure(502, null))
        assertEquals(SangatConnect.Failure.Retry(60_000L), SangatConnect.afterFailure(429, null))
    }

    private fun upgrade(
        url: String,
        token: String,
    ): AtomicReference<Pair<Int?, Boolean>> {
        val outcome = AtomicReference<Pair<Int?, Boolean>>()
        val done = CountDownLatch(1)
        val socket =
            client.newWebSocket(
                SangatConnect.request(url, token, "dev.test"),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        outcome.set(response.code to true)
                        done.countDown()
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        outcome.set(response?.code to false)
                        done.countDown()
                    }
                },
            )
        assertTrue("the upgrade never finished", done.await(5, TimeUnit.SECONDS))
        socket.cancel()
        return outcome
    }

    @Test
    fun `the upgrade carries the bearer to the hub url`() {
        hub.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())

        val outcome = upgrade(hub.url("/ws").toString(), "device-session")

        assertEquals(101 to true, outcome.get())
        val request = requireNotNull(hub.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("Bearer device-session", request.headers["Authorization"])
        assertEquals("dev.test", request.headers["User-Agent"])
        assertEquals("/ws", request.url.encodedPath)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test
    fun `a redirect is not followed, so the bearer reaches no other host`() {
        hub.enqueue(MockResponse.Builder().code(307).addHeader("Location", elsewhere.url("/ws").toString()).build())
        elsewhere.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())

        val outcome = upgrade(hub.url("/ws").toString(), "device-session")

        assertEquals(307 to false, outcome.get())
        assertEquals("the redirect target was never contacted", 0, elsewhere.requestCount)
        assertEquals(1, hub.requestCount)
    }

    @Test
    fun `a 401 on the upgrade reaches the retry decision as 401 and is not retried`() {
        hub.enqueue(MockResponse.Builder().code(401).build())

        val (code, opened) = upgrade(hub.url("/ws").toString(), "revoked").get()

        assertFalse(opened)
        assertEquals(401, code)
        assertTrue(SangatConnect.afterFailure(code, null) is SangatConnect.Failure.Stop)
        assertEquals("one attempt only", 1, hub.requestCount)
    }

    @Test
    fun `a token OkHttp rejects raises IllegalArgumentException, which the client catches without logging it`() {
        val failure = runCatching { SangatConnect.request("https://hub.example/ws", "tok\nsecret", "dev.test") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}

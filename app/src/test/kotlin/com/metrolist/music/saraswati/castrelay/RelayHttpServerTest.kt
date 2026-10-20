/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 16 — the relay server over real sockets on 127.0.0.1, with a fake audio source.
package com.metrolist.music.saraswati.castrelay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom

class RelayHttpServerTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val audio = ByteArray(100) { it.toByte() }
    private val source = FakeSource(mapOf("vid1" to audio))
    private val session = RelaySession(SecureRandom())
    private val opaque = session.register(listOf("vid1")).getValue("vid1")
    private val servers = mutableListOf<RelayHttpServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private fun startServer(
        s: RelaySession = session,
        src: RelayAudioSource = source,
    ): Pair<RelayHttpServer, Int> {
        val server = RelayHttpServer(loopback, s, src)
        servers += server
        return server to server.start()
    }

    private fun get(
        port: Int,
        path: String,
        extraHeaders: String = "",
        method: String = "GET",
    ): Reply = exchange(port, "$method $path HTTP/1.1\r\nHost: x\r\n$extraHeaders\r\n")

    private fun tokenPath(id: String = opaque) = "/${session.token}/$id"

    @Test
    fun `serves a registered song with 200 and its content type`() {
        val (_, port) = startServer()
        val reply = get(port, tokenPath())
        assertEquals(200, reply.status)
        assertTrue(reply.head.contains("Content-Type: audio/webm"))
        assertTrue(reply.head.contains("Access-Control-Allow-Origin: *"))
        assertEquals(audio.toList(), reply.body.toList())
    }

    @Test
    fun `a byte range gets 206 with Content-Range`() {
        val (_, port) = startServer()
        val reply = get(port, tokenPath(), "Range: bytes=10-19\r\n")
        assertEquals(206, reply.status)
        assertTrue(reply.head.contains("Content-Range: bytes 10-19/100"))
        assertEquals(audio.copyOfRange(10, 20).toList(), reply.body.toList())
    }

    @Test
    fun `an open-ended range works`() {
        val (_, port) = startServer()
        val reply = get(port, tokenPath(), "Range: bytes=90-\r\n")
        assertEquals(206, reply.status)
        assertTrue(reply.head.contains("Content-Range: bytes 90-99/100"))
        assertEquals(audio.copyOfRange(90, 100).toList(), reply.body.toList())
    }

    @Test
    fun `an unsatisfiable range gets 416`() {
        val (_, port) = startServer()
        val reply = get(port, tokenPath(), "Range: bytes=500-\r\n")
        assertEquals(416, reply.status)
        assertTrue(reply.head.contains("Content-Range: bytes */100"))
    }

    @Test
    fun `wrong token gets 403 and no body`() {
        val (_, port) = startServer()
        val reply = get(port, "/${"0".repeat(64)}/$opaque")
        assertEquals(403, reply.status)
        assertEquals(0, reply.body.size)
    }

    @Test
    fun `unknown opaque id gets 404`() {
        val (_, port) = startServer()
        assertEquals(404, get(port, tokenPath("f".repeat(32))).status)
    }

    @Test
    fun `a video id or parent-directory path gets 404`() {
        val (_, port) = startServer()
        assertEquals(404, get(port, tokenPath("vid1")).status)
        assertEquals(404, get(port, "/${session.token}/../$opaque").status)
        assertEquals(404, get(port, "/${session.token}/../../etc/passwd").status)
    }

    @Test
    fun `POST gets 405`() {
        val (_, port) = startServer()
        assertEquals(405, get(port, tokenPath(), method = "POST").status)
    }

    @Test
    fun `an oversized request line is dropped`() {
        val (_, port) = startServer()
        val longPath = "/" + "a".repeat(9000)
        val reply = exchange(port, "GET $longPath HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(0, reply.raw.size)
    }

    @Test
    fun `a fifth concurrent connection is closed without a response`() {
        val (_, port) = startServer()
        val held = (1..4).map { Socket(loopback, port).also { s -> s.getOutputStream().write("GET /".toByteArray()) } }
        try {
            Thread.sleep(200)
            val reply = exchange(port, "GET ${tokenPath()} HTTP/1.1\r\nHost: x\r\n\r\n")
            assertEquals(0, reply.raw.size)
        } finally {
            held.forEach { it.close() }
        }
    }

    @Test
    fun `binds only to the given address`() {
        val (server, port) = startServer()
        assertTrue(server.urlFor(opaque).startsWith("http://127.0.0.1:$port/"))
        try {
            RelayHttpServer(InetAddress.getByName("0.0.0.0"), session, source)
            fail("wildcard bind must be refused")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `stop closes the port`() {
        val (server, port) = startServer()
        server.stop()
        try {
            Socket(loopback, port).close()
            fail("port should be closed after stop")
        } catch (expected: ConnectException) {
        }
    }

    @Test
    fun `a new session's token rejects the old one`() {
        val other = RelaySession(SecureRandom())
        val (_, port) = startServer(s = other)
        val reply = get(port, tokenPath())
        assertEquals(403, reply.status)
        assertFalse(reply.body.isNotEmpty())
    }

    private class FakeSource(
        private val songs: Map<String, ByteArray>,
    ) : RelayAudioSource {
        override suspend fun open(
            videoId: String,
            range: LongRange?,
        ): RelayStream? {
            val data = songs[videoId] ?: return null
            val start = (range?.first ?: 0L).coerceAtMost(data.size.toLong()).toInt()
            return RelayStream("audio/webm", data.size.toLong(), ByteArrayInputStream(data, start, data.size - start))
        }
    }

    private class Reply(
        val raw: ByteArray,
    ) {
        private val text = String(raw, Charsets.ISO_8859_1)
        private val split = text.indexOf("\r\n\r\n").let { if (it < 0) text.length else it }
        val head: String = text.substring(0, split)
        val body: ByteArray = if (split + 4 <= raw.size) raw.copyOfRange(split + 4, raw.size) else ByteArray(0)
        val status: Int = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun exchange(
        port: Int,
        request: String,
    ): Reply {
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val n = socket.getInputStream().read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            } catch (e: IOException) {
                // A reset after a dropped request counts as no response.
            }
            return Reply(out.toByteArray())
        }
    }
}

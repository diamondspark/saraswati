/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 16 — the LAN relay a Cast receiver fetches audio from. Plain sockets, bound to one
// address (the phone's Wi-Fi IP), never a file or a listing. The only things a request can reach are a
// song the current session registered, behind a token only this session knows. Limits on connections,
// request size and header time keep a hostile neighbour from tying the phone up.
package com.metrolist.music.saraswati.castrelay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

class RelayHttpServer(
    private val bind: InetAddress,
    private val session: RelaySession,
    private val source: RelayAudioSource,
) {
    init {
        require(!bind.isAnyLocalAddress) { "the relay must bind to one address, never the wildcard" }
    }

    private val lock = Any()
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val openSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null
    private var port: Int = 0

    /** Binds and starts accepting. Returns the ephemeral port. */
    fun start(): Int =
        synchronized(lock) {
            check(server == null) { "relay already started" }
            val socket = ServerSocket(0, BACKLOG, bind)
            val pool =
                Executors.newCachedThreadPool { task ->
                    Thread(task, "saraswati-cast-relay-worker").apply { isDaemon = true }
                }
            server = socket
            workers = pool
            port = socket.localPort
            Thread({ acceptLoop(socket, pool) }, "saraswati-cast-relay-accept").apply {
                isDaemon = true
                start()
            }
            port
        }

    fun stop() {
        synchronized(lock) {
            server?.runCatching { close() }
            workers?.shutdownNow()
            openSockets.forEach { socket -> socket.runCatching { close() } }
            openSockets.clear()
            server = null
            workers = null
            port = 0
        }
    }

    fun urlFor(opaqueId: String): String {
        val bound = synchronized(lock) { port }
        check(bound > 0) { "relay not started" }
        return "http://${bind.hostAddress}:$bound/${session.token}/$opaqueId"
    }

    private fun acceptLoop(
        socket: ServerSocket,
        pool: ExecutorService,
    ) {
        while (!socket.isClosed) {
            val client =
                try {
                    socket.accept()
                } catch (e: IOException) {
                    return
                }
            if (!slots.tryAcquire()) {
                closeQuietly(client)
                continue
            }
            openSockets += client
            try {
                pool.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                openSockets -= client
                slots.release()
                closeQuietly(client)
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            handle(client)
        } catch (e: IOException) {
            // The peer went away or stalled; nothing to answer.
        } catch (e: Throwable) {
            // A worker thread must never take the app down: the stream resolver can throw PlaybackException, and
            // a read can fail mid-song with anything. The connection is closed below.
        } finally {
            openSockets -= client
            slots.release()
            closeQuietly(client)
        }
    }

    private fun handle(client: Socket) {
        val deadline = System.nanoTime() + HEADER_TIMEOUT_NANOS
        val input = client.getInputStream()
        val requestLine = readLine(client, input, deadline) ?: return
        val headers = readHeaders(client, input, deadline) ?: return
        val output = client.getOutputStream()

        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
            respond(output, 400, "Bad Request")
            return
        }
        val method = parts[0]
        if (method != "GET" && method != "HEAD") {
            respond(output, 405, "Method Not Allowed", extra = "Allow: GET, HEAD\r\n")
            return
        }
        val isHead = method == "HEAD"

        val segments = parts[1].removePrefix("/").split('/')
        if (!parts[1].startsWith("/") || segments.size != 2 || segments.any { it.isEmpty() }) {
            respond(output, 404, "Not Found")
            return
        }
        if (!MessageDigest.isEqual(segments[0].toByteArray(), session.token.toByteArray())) {
            respond(output, 403, "Forbidden")
            return
        }
        val videoId = session.resolve(segments[1])
        if (videoId == null) {
            respond(output, 404, "Not Found")
            return
        }

        val range = parseRange(headers.filter { it.first.equals("range", ignoreCase = true) }.map { it.second })
        if (range == null) {
            serveWhole(output, videoId, isHead)
        } else {
            serveRange(output, videoId, range, isHead)
        }
    }

    private fun serveWhole(
        output: OutputStream,
        videoId: String,
        isHead: Boolean,
    ) {
        val opened = openSource(videoId, null)
        val stream = opened.stream
        if (stream == null) {
            respondUnavailable(output, opened)
            return
        }
        stream.input.use {
            val total = stream.totalLength
            val extra = if (total != null) "Content-Length: $total\r\n" else ""
            writeHead(output, 200, "OK", stream.mimeType, extra)
            if (!isHead) copy(it, output, total ?: Long.MAX_VALUE)
        }
    }

    private fun serveRange(
        output: OutputStream,
        videoId: String,
        range: ByteRange,
        isHead: Boolean,
    ) {
        val opened = openSource(videoId, LongRange(range.first, range.last ?: Long.MAX_VALUE))
        val stream = opened.stream
        if (stream == null) {
            respondUnavailable(output, opened)
            return
        }
        stream.input.use {
            val total = stream.totalLength
            if (total == null) {
                respond(output, 416, "Range Not Satisfiable")
                return
            }
            if (range.first >= total) {
                respond(output, 416, "Range Not Satisfiable", extra = "Content-Range: bytes */$total\r\n")
                return
            }
            val end = minOf(range.last ?: (total - 1), total - 1)
            val length = end - range.first + 1
            val extra = "Content-Range: bytes ${range.first}-$end/$total\r\nContent-Length: $length\r\n"
            writeHead(output, 206, "Partial Content", stream.mimeType, extra)
            if (!isHead) copy(it, output, length)
        }
    }

    /** The source throws PlaybackException (not an IOException) when the stream cannot be resolved; any failure is a 502. */
    private fun openSource(
        videoId: String,
        range: LongRange?,
    ): Opened =
        try {
            Opened(runBlocking(Dispatchers.IO) { source.open(videoId, range) }, failed = false)
        } catch (e: Throwable) {
            Opened(null, failed = true)
        }

    private fun respondUnavailable(
        output: OutputStream,
        opened: Opened,
    ) {
        if (opened.failed) respond(output, 502, "Bad Gateway") else respond(output, 404, "Not Found")
    }

    private class Opened(
        val stream: RelayStream?,
        val failed: Boolean,
    )

    /** Reads one CRLF-terminated line, or null when the line is too long, the peer closed, or time ran out. */
    private fun readLine(
        client: Socket,
        input: InputStream,
        deadline: Long,
    ): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = readByte(client, input, deadline)
            if (byte < 0) return null
            if (byte == '\n'.code) break
            if (buffer.length >= MAX_LINE_BYTES) return null
            buffer.append(byte.toChar())
        }
        return buffer.toString().removeSuffix("\r")
    }

    /** Header name/value pairs, or null when there are more than [MAX_HEADERS], a line is too long, or the block never ends. */
    private fun readHeaders(
        client: Socket,
        input: InputStream,
        deadline: Long,
    ): List<Pair<String, String>>? {
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = readLine(client, input, deadline) ?: return null
            if (line.isEmpty()) return headers
            if (headers.size >= MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
    }

    private fun readByte(
        client: Socket,
        input: InputStream,
        deadline: Long,
    ): Int {
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0) throw SocketTimeoutException("header timeout")
        client.soTimeout = maxOf(1, (remainingNanos / 1_000_000L).toInt())
        return input.read()
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        limit: Long,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        output.flush()
    }

    private fun writeHead(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        extra: String,
    ) {
        val head =
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Accept-Ranges: bytes\r\n" +
                COMMON_HEADERS +
                extra +
                "\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
    }

    private fun respond(
        output: OutputStream,
        code: Int,
        reason: String,
        extra: String = "",
    ) {
        val head =
            "HTTP/1.1 $code $reason\r\n" +
                COMMON_HEADERS +
                "Content-Length: 0\r\n" +
                extra +
                "\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // Already closed.
        }
    }

    private data class ByteRange(
        val first: Long,
        val last: Long?,
    )

    private companion object {
        const val MAX_CONNECTIONS = 4
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADERS = 50
        const val HEADER_TIMEOUT_NANOS = 10_000_000_000L
        const val BACKLOG = 8
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val COMMON_HEADERS =
            "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n"

        private val SINGLE_RANGE = Regex("""bytes=(\d+)-(\d*)""")

        /**
         * A single `bytes=a-b` or `bytes=a-` range. Anything else (several ranges, suffix ranges, junk) is
         * ignored, which serves the whole song as RFC 9110 allows.
         */
        fun parseRange(values: List<String>): ByteRange? {
            if (values.size != 1) return null
            val match = SINGLE_RANGE.matchEntire(values[0].trim()) ?: return null
            val first = match.groupValues[1].toLongOrNull() ?: return null
            val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
            if (match.groupValues[2].isNotEmpty() && last == null) return null
            if (last != null && last < first) return null
            return ByteRange(first, last)
        }
    }
}

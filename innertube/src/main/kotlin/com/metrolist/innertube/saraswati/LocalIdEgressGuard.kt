/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 2 — the last line of defence for songs on this phone. A local song's id
// (`local:<MediaStore id>`) names a file on this device, so no request to YouTube or anyone else may carry it.
// The app-side guards (stream resolve, lyrics, downloads, history) stop it earlier; this refuses any request
// that still names one, before a socket is opened.
package com.metrolist.innertube.saraswati

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.content.TextContent
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class LocalSongEgressException(
    message: String = "A song on this phone cannot be sent over the network",
) : IllegalStateException(message)

// The `local` must start the id or follow a non-letter (`"local:42"`, `=local%3A42`, `%20local:42`), so a word that
// merely ends in "local" (`biolocal:3`) is not refused. Encoded forms (`%3A`, double-encoded `%253A`) are refused too.
private val LOCAL_ID = Regex("(?i)(?<![a-z])local(:|%3A|%253A)\\d+")

internal fun mentionsLocalId(text: String): Boolean = LOCAL_ID.containsMatchIn(text)

val LocalIdEgressGuard =
    createClientPlugin("LocalIdEgressGuard") {
        client.sendPipeline.intercept(HttpSendPipeline.Before) { content ->
            if (mentionsLocalId(context.url.buildString()) ||
                (content is TextContent && mentionsLocalId(content.text))
            ) {
                throw LocalSongEgressException()
            }
        }
    }

/**
 * The OkHttp counterpart of [LocalIdEgressGuard]: refuses a request whose URL or text body names a song on this phone,
 * before the call reaches the network. Install it on every OkHttp client that could carry song data.
 */
object LocalIdEgressInterceptor : Interceptor {
    // Bodies up to this size (or of unknown length) are read and checked; a larger body is not a song request.
    private const val MAX_CHECKED_BODY = 1L shl 20

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        val bodyNamesLocal =
            body != null && body.contentLength() <= MAX_CHECKED_BODY &&
                Buffer().also { body.writeTo(it) }.readUtf8().let(::mentionsLocalId)
        if (mentionsLocalId(request.url.toString()) || bodyNamesLocal) throw LocalSongEgressException()
        return chain.proceed(request)
    }
}

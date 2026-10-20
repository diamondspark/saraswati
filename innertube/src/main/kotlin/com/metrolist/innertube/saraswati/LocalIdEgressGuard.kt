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

class LocalSongEgressException(
    message: String = "A song on this phone cannot be sent over the network",
) : IllegalStateException(message)

private val LOCAL_ID = Regex("(?i)local(:|%3A)\\d+")

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

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 8 — resolves what the owner typed into a station's video
// field (a bare video id, a YouTube or YouTube Music link, a shared message
// with one) to the video id the station is seeded with. Only YouTube hosts
// and only a video resolve: a playlist, channel, other site or wrong-length
// id gives null, so the editor can refuse it rather than save a station that
// would not play.
package com.metrolist.music.saraswati.family

import com.metrolist.music.saraswati.security.DeepLinkGuard
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object StationVideo {
    /**
     * What a YouTube video id looks like: eleven letters, digits, "_" or "-". The one definition the
     * editor, the station cache and the car all use, so a station one accepts is never dropped by another.
     */
    const val ID_PATTERN = "[A-Za-z0-9_-]{11}"
    private val VIDEO_ID = Regex("^$ID_PATTERN$")

    fun isVideoId(text: String): Boolean = VIDEO_ID.matches(text)

    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val ID_IN_PATH = setOf("shorts", "embed", "live", "v")

    fun videoIdFrom(input: String): String? {
        val text = input.trim()
        if (VIDEO_ID.matches(text)) return text
        val url = text.toHttpUrlOrNull() ?: DeepLinkGuard.firstUrlIn(text)?.toHttpUrlOrNull() ?: return null
        return candidate(url)?.takeIf { VIDEO_ID.matches(it) }
    }

    private fun candidate(url: HttpUrl): String? {
        val segments = url.pathSegments
        return when (url.host) {
            "youtu.be" -> segments.firstOrNull()
            in YOUTUBE_HOSTS ->
                when (segments.firstOrNull()) {
                    "watch" -> url.queryParameter("v")
                    in ID_IN_PATH -> segments.getOrNull(1)
                    else -> null
                }
            else -> null
        }
    }
}

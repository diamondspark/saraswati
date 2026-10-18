/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: audit A9.2/A9.3 — handleDeepLinkIntent used to accept data or
// shared text from any action, scheme or host, then spliced raw ids straight
// into navigation routes (A9.2) and called Uri.getQueryParameter on URIs that
// can be non-hierarchical, which throws (A9.3). This gate is plain-JUnit
// testable (no Android dependency), matching the project's existing
// policy-object pattern (LoginUrlPolicy, SaraswatiDeepLinkPolicy). It is a
// separate, broader check than SaraswatiDeepLinkPolicy: that object only
// vetoes the Listen Together join path; this one decides which links are
// worth parsing at all, and is applied in addition to it, never instead.
package com.metrolist.music.saraswati.security

import java.util.Locale

object DeepLinkGuard {
    private val ACCEPTED_HOSTS =
        setOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "music.youtube.com",
            "youtu.be",
        )

    private const val VND_YOUTUBE_SCHEME = "vnd.youtube"

    private val VALID_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val FIRST_URL = Regex("https?://\\S+")

    /**
     * Whether a link is even worth parsing further. `vnd.youtube` is accepted
     * regardless of [isHierarchical] (it is commonly opaque, e.g.
     * "vnd.youtube:VIDEO_ID"); every other scheme must be `http`/`https`,
     * hierarchical, and on one of [ACCEPTED_HOSTS].
     */
    fun isAcceptedLink(
        scheme: String?,
        host: String?,
        isHierarchical: Boolean,
    ): Boolean {
        if (scheme.equals(VND_YOUTUBE_SCHEME, ignoreCase = true)) return true
        if (!isHierarchical) return false

        val normalizedScheme = scheme?.lowercase(Locale.ROOT)
        if (normalizedScheme != "http" && normalizedScheme != "https") return false

        val normalizedHost = host?.lowercase(Locale.ROOT) ?: return false
        return normalizedHost in ACCEPTED_HOSTS
    }

    /** A video/playlist/browse/artist id: 1-64 chars, no path or query separators. */
    fun isValidId(id: String): Boolean = VALID_ID.matches(id)

    /** The first `https?://\S+` token in free text (e.g. a shared "Song: <url>"), or null. */
    fun firstUrlIn(text: String): String? = FIRST_URL.find(text)?.value
}

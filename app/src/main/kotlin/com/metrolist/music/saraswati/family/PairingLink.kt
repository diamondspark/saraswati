/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the family pairing link: `https://<hub host>/pair#<token>`.
//
// The token rides in the fragment, which a browser never sends to the server,
// so a tap that lands in a browser instead of the app does not put the token
// in the hub's logs. The parser is deliberately narrower than "some HTTPS
// URL": the hub's own host, its own port, the exact path `/pair`, no
// userinfo, no query, and a fragment that is exactly a hex token. It is the
// pairing counterpart of DeepLinkGuard and is applied before it, so a
// pairing link never reaches the YouTube route-building code.
package com.metrolist.music.saraswati.family

import com.metrolist.music.saraswati.security.DeepLinkGuard
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PairingLink {
    private const val PATH = "pair"
    private val TOKEN = Regex("^[0-9a-f]{32,128}$")

    fun isValidToken(token: String): Boolean = TOKEN.matches(token)

    /** The link to hand to a family member, or null when the hub is not configured or [token] is unusable. */
    fun build(
        hubBaseUrl: String,
        token: String,
    ): String? {
        val base = hubBaseUrl.trim().toHttpUrlOrNull() ?: return null
        if (!isValidToken(token)) return null
        return base
            .newBuilder()
            .encodedPath("/$PATH")
            .query(null)
            .encodedFragment(token)
            .build()
            .toString()
    }

    /** The token in a pairing link for [hubBaseUrl]'s hub, or null for anything that is not exactly one. */
    fun tokenFrom(
        link: String,
        hubBaseUrl: String,
    ): String? {
        val hub = hubBaseUrl.trim().toHttpUrlOrNull() ?: return null
        val url: HttpUrl = link.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || !hub.isHttps) return null
        if (url.host != hub.host || url.port != hub.port) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.encodedPath != "/$PATH" || url.encodedQuery != null) return null
        return url.encodedFragment?.takeIf { isValidToken(it) }
    }

    /**
     * What the join screen accepts: a bare token, a pairing link, or text
     * (a shared message) whose first link is a pairing link.
     */
    fun tokenFromInput(
        input: String,
        hubBaseUrl: String,
    ): String? {
        val trimmed = input.trim()
        if (isValidToken(trimmed)) return trimmed
        val link = DeepLinkGuard.firstUrlIn(trimmed) ?: return null
        return tokenFrom(link, hubBaseUrl)
    }
}

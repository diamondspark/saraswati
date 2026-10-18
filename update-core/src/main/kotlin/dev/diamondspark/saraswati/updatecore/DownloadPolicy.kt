/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — the download client follows HTTP redirects to fetch a
// release asset (GitHub serves the actual bytes from a signed, short-lived
// URL on one of its *.githubusercontent.com CDN hosts, not from github.com
// itself), and a redirect is exactly where a MITM'd or compromised response
// can walk the client off to an attacker-controlled host one hop at a time.
//
// Every check below parses the candidate URL with java.net.URI and compares
// its scheme and host *exactly* against a fixed allowlist — never a
// substring/startsWith/endsWith check on the raw string. That distinction
// is the whole point: `host == "evil.example"` for
// "https://githubusercontent.com.evil.example/x" is false, where a naive
// `url.contains("githubusercontent.com")` or
// `host.startsWith("githubusercontent.com")` would both wrongly accept it.
// java.net.URI.getHost() is also what keeps a userinfo trick such as
// "https://objects.githubusercontent.com@evil.example/x" from being read as
// the trusted host: it correctly reports "evil.example" there, not
// "objects.githubusercontent.com".
package dev.diamondspark.saraswati.updatecore

import java.net.URI
import java.net.URISyntaxException

object DownloadPolicy {
    const val MAX_APK_BYTES = 104_857_600L
    const val MAX_REDIRECTS = 3

    // Hosts a redirect may legitimately start from: the release page itself,
    // or one CDN hop handing off to another (observed GitHub behavior).
    private val ALLOWED_REDIRECT_SOURCE_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    // Hosts a redirect may land on: GitHub's release-asset CDN only, never
    // github.com itself (an asset download never redirects back to the web
    // UI host).
    private val ALLOWED_REDIRECT_TARGET_HOSTS = setOf(
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    fun isAllowedStartUrl(url: String): Boolean = url.startsWith(RELEASE_URL_PREFIX)

    fun isAllowedRedirect(from: String, to: String): Boolean {
        val fromHost = httpsHostOf(from) ?: return false
        val toHost = httpsHostOf(to) ?: return false
        if (fromHost !in ALLOWED_REDIRECT_SOURCE_HOSTS) return false
        return toHost in ALLOWED_REDIRECT_TARGET_HOSTS
    }

    /** The exact host of an `https` URL, or null if it isn't a well-formed `https` URL. */
    private fun httpsHostOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        return uri.host?.lowercase()
    }
}

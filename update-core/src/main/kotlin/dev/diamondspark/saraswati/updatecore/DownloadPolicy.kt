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
//
// isAllowedStartUrl does the same for the *path*, not just the host. A
// literal `startsWith(RELEASE_URL_PREFIX)` on the raw string — this
// module's original approach — pins the start of the string but never
// resolves `.`/`..` segments, so
// ".../releases/download/../../../../attacker/repo/releases/download/v1/x.apk"
// still passes it character-for-character, while any real HTTP client
// (verified against this project's own OkHttp) resolves that same string to
// "https://github.com/attacker/repo/releases/download/v1/x.apk" — a
// different repository entirely.
//
// The fix deliberately does *not* try to replicate RFC 3986 dot-segment
// *resolution* itself (collapse `..` by popping the previous segment) and
// then compare: that would require this module's resolution algorithm to
// agree, byte for byte, with whatever HTTP client Task 5 ends up using for
// every edge case (empty segments, trailing dots, double-encoding, ...),
// and `java.net.URI.normalize()` already demonstrates how easy that is to
// get subtly wrong — it operates on the raw/encoded path and treats a
// percent-encoded dot segment (`%2e%2e`) as an opaque segment name, so it
// does *not* collapse it, even though the already-decoded path underneath
// (`URI.getPath()`, which *does* decode `%2e%2e` to `..` for us) is a real
// `../../` that a client resolves away. Instead, [decodedPathSegmentsOrNull]
// decodes the path (via `URI.getPath()`) and flatly rejects the whole URL
// if *any* segment is `.` or `..` — a legitimate release-asset URL never
// contains one, so there is no resolution semantics to get wrong: nothing
// here is ever collapsed or resolved, it is simply refused outright.
package dev.diamondspark.saraswati.updatecore

import java.net.URI
import java.net.URISyntaxException

object DownloadPolicy {
    const val MAX_APK_BYTES = 104_857_600L
    const val MAX_REDIRECTS = 3

    private const val RELEASE_HOST = "github.com"

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

    // RELEASE_URL_PREFIX's own path, decoded into segments the same way a
    // candidate URL's path is, so the comparison below is segment-for-
    // segment, not a raw-string prefix.
    private val RELEASE_PATH_PREFIX_SEGMENTS: List<String> =
        requireNotNull(decodedPathSegmentsOrNull(URI(RELEASE_URL_PREFIX).path)) {
            "RELEASE_URL_PREFIX's own path must not contain a dot segment"
        }

    fun isAllowedStartUrl(url: String): Boolean {
        val uri = parseUri(url) ?: return false
        if (httpsHostOf(uri) != RELEASE_HOST) return false
        val segments = decodedPathSegmentsOrNull(uri.path ?: return false) ?: return false
        // Strictly more segments than the prefix: a release asset URL always
        // has a tag and a filename after ".../releases/download/".
        if (segments.size <= RELEASE_PATH_PREFIX_SEGMENTS.size) return false
        return segments.subList(0, RELEASE_PATH_PREFIX_SEGMENTS.size) == RELEASE_PATH_PREFIX_SEGMENTS
    }

    fun isAllowedRedirect(from: String, to: String): Boolean {
        val fromHost = httpsHostOf(from) ?: return false
        val toHost = httpsHostOf(to) ?: return false
        if (fromHost !in ALLOWED_REDIRECT_SOURCE_HOSTS) return false
        return toHost in ALLOWED_REDIRECT_TARGET_HOSTS
    }

    private fun parseUri(url: String): URI? = try {
        URI(url)
    } catch (e: URISyntaxException) {
        null
    }

    /** The exact host of an `https` URL, or null if it isn't a well-formed `https` URL. */
    private fun httpsHostOf(url: String): String? = parseUri(url)?.let(::httpsHostOf)

    private fun httpsHostOf(uri: URI): String? {
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        return uri.host?.lowercase()
    }

    /**
     * Splits an already percent-decoded path into its non-empty segments,
     * or returns null if any segment is `.` or `..`. No resolution is
     * attempted — a dot segment anywhere disqualifies the whole URL,
     * full stop, rather than trying to compute where it would actually
     * end up.
     */
    private fun decodedPathSegmentsOrNull(decodedPath: String): List<String>? {
        val segments = decodedPath.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        return segments
    }
}

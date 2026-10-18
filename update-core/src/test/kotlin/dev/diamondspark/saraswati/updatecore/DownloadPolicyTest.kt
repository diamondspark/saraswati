/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — the download path follows HTTP redirects, which is
// exactly where a compromised or MITM'd response can walk the client off to
// an attacker host one hop at a time. Every allow/deny decision here is
// host- and scheme-exact (never substring/suffix matching), specifically to
// defend against lookalike hosts such as "githubusercontent.com.evil.example".
package dev.diamondspark.saraswati.updatecore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RELEASE_URL = RELEASE_URL_PREFIX + "v1.0.0/saraswati-v1.0.0.apk"

class DownloadPolicyTest {

    @Test
    fun `allows the release prefix`() {
        assertTrue(DownloadPolicy.isAllowedStartUrl(RELEASE_URL))
    }

    @Test
    fun `rejects http`() {
        val httpUrl = "http://github.com/diamondspark/saraswati/releases/download/v1.0.0/saraswati-v1.0.0.apk"
        assertFalse(DownloadPolicy.isAllowedStartUrl(httpUrl))
    }

    @Test
    fun `rejects another github repo`() {
        val otherRepo = "https://github.com/someone-else/other-repo/releases/download/v1.0.0/x.apk"
        assertFalse(DownloadPolicy.isAllowedStartUrl(otherRepo))
    }

    @Test
    fun `rejects a userinfo trick on the release host`() {
        // "https://github.com@evil.example/..." must not be mistaken for a
        // github.com URL by a check that only looks at the start of the
        // string without parsing the authority.
        val trick = "https://github.com@evil.example/diamondspark/saraswati/releases/download/v1.0.0/x.apk"
        assertFalse(DownloadPolicy.isAllowedStartUrl(trick))
    }

    @Test
    fun `rejects a dot-segment path traversal to another repo`() {
        // A literal startsWith(RELEASE_URL_PREFIX) check passes this
        // character-for-character, but a real HTTP client (verified
        // against this project's own OkHttp) resolves the ".." segments
        // and actually requests "https://github.com/attacker/repo/...".
        val traversal =
            "https://github.com/diamondspark/saraswati/releases/download/../../../../attacker/repo/releases/download/v1/evil.apk"
        assertFalse(DownloadPolicy.isAllowedStartUrl(traversal))
    }

    @Test
    fun `rejects a percent-encoded dot-segment path traversal`() {
        // "%2e%2e" decodes to "..": java.net.URI.getPath() performs that
        // decode for us, so this must be caught exactly like the literal
        // "../" case above, not slip through as an opaque segment the way
        // java.net.URI.normalize() alone would leave it (normalize() never
        // decodes before collapsing dot segments).
        val encodedTraversal =
            "https://github.com/diamondspark/saraswati/releases/download/%2e%2e/%2e%2e/attacker/repo/releases/download/v1/evil.apk"
        assertFalse(DownloadPolicy.isAllowedStartUrl(encodedTraversal))
    }

    @Test
    fun `rejects a single dot-segment that only partially escapes the release path`() {
        // Only one ".." — nowhere near enough to reach a different repo —
        // must still be rejected: this module never tries to resolve where
        // a dot segment "actually" ends up, it refuses any URL containing
        // one outright.
        val partialEscape = RELEASE_URL_PREFIX + "../evil/v1/evil.apk"
        assertFalse(DownloadPolicy.isAllowedStartUrl(partialEscape))
    }

    @Test
    fun `rejects a lone dot segment in the path`() {
        val dotSegment = RELEASE_URL_PREFIX + "./v1.0.0/evil.apk"
        assertFalse(DownloadPolicy.isAllowedStartUrl(dotSegment))
    }

    @Test
    fun `allows a redirect to objects githubusercontent com`() {
        assertTrue(DownloadPolicy.isAllowedRedirect(RELEASE_URL, "https://objects.githubusercontent.com/some/asset/path"))
    }

    @Test
    fun `allows a redirect to release-assets githubusercontent com`() {
        assertTrue(DownloadPolicy.isAllowedRedirect(RELEASE_URL, "https://release-assets.githubusercontent.com/some/asset/path"))
    }

    @Test
    fun `rejects a redirect to evil example`() {
        assertFalse(DownloadPolicy.isAllowedRedirect(RELEASE_URL, "https://evil.example/saraswati.apk"))
    }

    @Test
    fun `rejects a lookalike host such as githubusercontent com evil example`() {
        assertFalse(
            DownloadPolicy.isAllowedRedirect(
                RELEASE_URL,
                "https://githubusercontent.com.evil.example/saraswati.apk",
            ),
        )
    }

    @Test
    fun `rejects a redirect to an http target`() {
        assertFalse(DownloadPolicy.isAllowedRedirect(RELEASE_URL, "http://objects.githubusercontent.com/some/path"))
    }

    @Test
    fun `rejects a redirect whose source is not a trusted host`() {
        assertFalse(
            DownloadPolicy.isAllowedRedirect(
                "https://evil.example/initial",
                "https://objects.githubusercontent.com/some/path",
            ),
        )
    }

    @Test
    fun `rejects a redirect to a userinfo trick`() {
        val trick = "https://objects.githubusercontent.com@evil.example/path"
        assertFalse(DownloadPolicy.isAllowedRedirect(RELEASE_URL, trick))
    }
}

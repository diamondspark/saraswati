/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the broader deep-link gate (audit
// A9.2/A9.3). This is a different, additional policy from
// SaraswatiDeepLinkPolicyTest (SaraswatiFeaturesTest.kt), which only covers
// the Listen Together early return.
package com.metrolist.music.saraswati.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkGuardTest {
    @Test
    fun `accepts a youtube watch link`() {
        assertTrue(DeepLinkGuard.isAcceptedLink(scheme = "https", host = "youtube.com", isHierarchical = true))
        assertTrue(DeepLinkGuard.isAcceptedLink(scheme = "http", host = "www.youtube.com", isHierarchical = true))
    }

    @Test
    fun `accepts music youtube com and youtu be`() {
        assertTrue(DeepLinkGuard.isAcceptedLink(scheme = "https", host = "music.youtube.com", isHierarchical = true))
        assertTrue(DeepLinkGuard.isAcceptedLink(scheme = "https", host = "m.youtube.com", isHierarchical = true))
        assertTrue(DeepLinkGuard.isAcceptedLink(scheme = "https", host = "youtu.be", isHierarchical = true))
    }

    @Test
    fun `accepts the vnd youtube scheme`() {
        // Opaque form ("vnd.youtube:ID") has no host and is not hierarchical.
        assertTrue(DeepLinkGuard.isAcceptedLink(scheme = "vnd.youtube", host = null, isHierarchical = false))
        assertTrue(DeepLinkGuard.isAcceptedLink(scheme = "VND.YOUTUBE", host = null, isHierarchical = false))
        // Hierarchical form ("vnd.youtube://ID") is accepted too.
        assertTrue(DeepLinkGuard.isAcceptedLink(scheme = "vnd.youtube", host = "abc", isHierarchical = true))
    }

    @Test
    fun `rejects other hosts and schemes`() {
        assertFalse(DeepLinkGuard.isAcceptedLink(scheme = "https", host = "evil.example", isHierarchical = true))
        assertFalse(DeepLinkGuard.isAcceptedLink(scheme = "https", host = "youtube.com.evil.example", isHierarchical = true))
        assertFalse(DeepLinkGuard.isAcceptedLink(scheme = "ftp", host = "youtube.com", isHierarchical = true))
        assertFalse(DeepLinkGuard.isAcceptedLink(scheme = "intent", host = "youtube.com", isHierarchical = true))
        assertFalse(DeepLinkGuard.isAcceptedLink(scheme = "https", host = null, isHierarchical = true))
    }

    @Test
    fun `rejects a non-hierarchical uri such as shared words`() {
        // Plain shared text with no scheme/host parses as non-hierarchical.
        assertFalse(DeepLinkGuard.isAcceptedLink(scheme = null, host = null, isHierarchical = false))
        // Even a http/https scheme on an allowed host is rejected if opaque.
        assertFalse(DeepLinkGuard.isAcceptedLink(scheme = "https", host = "youtube.com", isHierarchical = false))
    }

    @Test
    fun `rejects ids with a slash, question mark or percent sign`() {
        assertFalse(DeepLinkGuard.isValidId("abc/def"))
        assertFalse(DeepLinkGuard.isValidId("abc?def"))
        assertFalse(DeepLinkGuard.isValidId("abc%2Fdef"))
        assertFalse(DeepLinkGuard.isValidId(""))
    }

    @Test
    fun `accepts a standard 11 character id`() {
        assertTrue(DeepLinkGuard.isValidId("dQw4w9WgXcQ"))
        assertTrue(DeepLinkGuard.isValidId("a-b_c-D_09"))
    }

    @Test
    fun `firstUrlIn finds the link inside shared text`() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            DeepLinkGuard.firstUrlIn("Song: https://youtu.be/dQw4w9WgXcQ"),
        )
        assertEquals(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            DeepLinkGuard.firstUrlIn("Check this out -> https://music.youtube.com/watch?v=dQw4w9WgXcQ enjoy"),
        )
    }

    @Test
    fun `firstUrlIn returns null without a link`() {
        assertNull(DeepLinkGuard.firstUrlIn("just some shared words, no link here"))
        assertNull(DeepLinkGuard.firstUrlIn(""))
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests so the eight dropped-feature flags, and the
// Listen Together deep-link guard, never silently flip back on.
package com.metrolist.music.saraswati

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaraswatiFeaturesTest {
    @Test
    fun `ai lyrics translation is off`() = assertFalse(SaraswatiFeatures.AI_LYRICS)

    @Test
    fun `music recognition is off`() = assertFalse(SaraswatiFeatures.RECOGNITION)

    @Test
    fun `alarms are off`() = assertFalse(SaraswatiFeatures.ALARMS)

    @Test
    fun `widgets are off`() = assertFalse(SaraswatiFeatures.WIDGETS)

    @Test
    fun `proxy is off`() = assertFalse(SaraswatiFeatures.PROXY)

    @Test
    fun `remote cipher code is off`() = assertFalse(SaraswatiFeatures.REMOTE_CIPHER_CODE)
}

class SaraswatiDeepLinkPolicyTest {
    @Test
    fun `host listen is a listen link`() {
        assertTrue(SaraswatiDeepLinkPolicy.isListenTogetherLink(host = "listen", firstPathSegment = null))
    }

    @Test
    fun `host LISTEN is a listen link, case-insensitively`() {
        assertTrue(SaraswatiDeepLinkPolicy.isListenTogetherLink(host = "LISTEN", firstPathSegment = null))
    }

    @Test
    fun `first path segment listen is a listen link`() {
        assertTrue(SaraswatiDeepLinkPolicy.isListenTogetherLink(host = "metrolist.cc", firstPathSegment = "listen"))
    }

    @Test
    fun `youtube watch link is not a listen link`() {
        assertFalse(SaraswatiDeepLinkPolicy.isListenTogetherLink(host = "music.youtube.com", firstPathSegment = "watch"))
    }

    @Test
    fun `null host and null segment is not a listen link`() {
        assertFalse(SaraswatiDeepLinkPolicy.isListenTogetherLink(host = null, firstPathSegment = null))
    }
}

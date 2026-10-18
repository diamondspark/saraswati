/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the sign-in WebView's navigation gate
// (audit A7.1). The WebView is where a family member types their Google
// password, so this allowlist must stay exact-host, https-only, and never
// widen to a subdomain wildcard a lookalike host could exploit.
package com.metrolist.music.saraswati.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginUrlPolicyTest {
    @Test
    fun `accepts the Google sign-in entry`() {
        assertTrue(
            LoginUrlPolicy.isAllowedNavigation(
                "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com",
            ),
        )
    }

    @Test
    fun `accepts country sign-in domains`() {
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://accounts.google.co.in/signin"))
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://accounts.google.de/signin"))
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://accounts.google.com.br/signin"))
    }

    @Test
    fun `accepts YouTube Music pages`() {
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://music.youtube.com/"))
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://www.youtube.com/signin"))
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://m.youtube.com/signin"))
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://myaccount.google.com/"))
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://consent.google.com/"))
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://consent.youtube.com/"))
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://accounts.youtube.com/"))
    }

    @Test
    fun `rejects http`() {
        assertFalse(LoginUrlPolicy.isAllowedNavigation("http://accounts.google.com/ServiceLogin"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("http://music.youtube.com/"))
    }

    @Test
    fun `rejects lookalike hosts`() {
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https://accounts.google.com.evil.example/ServiceLogin"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https://evil-accounts.google.com/ServiceLogin"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https://accounts.google.evil.com/ServiceLogin"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https://accounts.google.xyz/ServiceLogin"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https://accountsxgoogle.com/ServiceLogin"))
    }

    @Test
    fun `rejects intent, javascript, file, content and data schemes`() {
        assertFalse(
            LoginUrlPolicy.isAllowedNavigation(
                "intent://scan/#Intent;scheme=zxing;package=com.evil;end",
            ),
        )
        assertFalse(LoginUrlPolicy.isAllowedNavigation("javascript:alert(document.cookie)"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("file:///etc/passwd"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("content://com.android.providers.media/1"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("data:text/html,<script>alert(1)</script>"))
    }

    @Test
    fun `rejects userinfo and odd ports`() {
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https://user:pass@accounts.google.com/"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https://accounts.google.com@evil.com/"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https://accounts.google.com:8443/"))
        assertTrue(LoginUrlPolicy.isAllowedNavigation("https://accounts.google.com:443/"))
    }

    @Test
    fun `rejects malformed urls`() {
        assertFalse(LoginUrlPolicy.isAllowedNavigation(""))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("not a url at all"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("accounts.google.com"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https://"))
        assertFalse(LoginUrlPolicy.isAllowedNavigation("https:///path/only"))
    }
}

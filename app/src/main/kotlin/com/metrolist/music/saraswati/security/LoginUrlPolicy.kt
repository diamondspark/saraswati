/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: the sign-in WebView is where a family member types their
// Google password (audit A7.1). This gate decides which page that WebView
// is allowed to navigate to — https only, an exact host allowlist (no
// subdomain wildcards beyond the one named country-domain pattern), so a
// lookalike host like accounts.google.com.evil.example or
// evil-accounts.google.com is always rejected rather than silently loaded.
package com.metrolist.music.saraswati.security

import java.net.URI
import java.util.Locale

object LoginUrlPolicy {
    private val EXACT_ALLOWED_HOSTS =
        setOf(
            "accounts.google.com",
            "accounts.youtube.com",
            "myaccount.google.com",
            "consent.google.com",
            // Google's own account-verification and recovery-options steps, which sign-in can
            // route through after the password. Blocked in a live sign-in, it left the page
            // stuck on a step Google considers required.
            "gds.google.com",
            "consent.youtube.com",
            "music.youtube.com",
            "www.youtube.com",
            "m.youtube.com",
        )

    // "accounts.google.<country domain>" — Google's per-country sign-in hosts, e.g.
    // accounts.google.co.in, accounts.google.de, accounts.google.com.br. Restricted to a
    // 2-letter ccTLD (optionally prefixed by "co." or "com."): all 2-letter TLDs are reserved
    // ISO-3166 country codes, so this can never widen to an attacker-registrable generic TLD
    // such as "accounts.google.xyz".
    private val COUNTRY_SIGNIN_HOST = Regex("^accounts\\.google\\.(?:[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})$")

    fun isAllowedNavigation(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false

        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.userInfo.isNullOrEmpty()) return false

        val port = uri.port
        if (port != -1 && port != 443) return false

        val host = uri.host?.lowercase(Locale.ROOT)
        if (host.isNullOrEmpty()) return false

        return host in EXACT_ALLOWED_HOSTS || COUNTRY_SIGNIN_HOST.matches(host)
    }
}

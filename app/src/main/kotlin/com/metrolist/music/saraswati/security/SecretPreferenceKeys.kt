/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: every preference whose value could let someone impersonate
// the family's YouTube account, an AI-translation or Last.fm account, or
// redirect this app's network traffic through an attacker-controlled host,
// if it ever reached a cloud backup or a restored/hand-copied legacy
// datastore file. SecretScrubber wipes every key here on every app start,
// except a [VAULT_SEALED] key whose value the vault actually accepts (i.e.
// one this app itself sealed): the YouTube cookie and the family hub
// credentials.
//
// SecretKeyCoverageTest reflects over every Preferences.Key in
// PreferenceKeysKt whose name looks secret-shaped and fails the build if a
// future upstream merge adds a new one without a conscious decision: it
// must land in [all] or in [NOT_SECRET] with a reason.
package com.metrolist.music.saraswati.security

import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.DiscordAvatarKey
import com.metrolist.music.constants.DiscordButton1UrlKey
import com.metrolist.music.constants.DiscordButton2UrlKey
import com.metrolist.music.constants.DiscordNameKey
import com.metrolist.music.constants.DiscordUsernameKey
import com.metrolist.music.constants.FamilyAdminKeyKey
import com.metrolist.music.constants.FamilyHubClaimSecretKey
import com.metrolist.music.constants.FamilyHubSessionTokenKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMSessionKey
import com.metrolist.music.constants.LastFMUsernameKey
import com.metrolist.music.constants.ListenTogetherSessionTimestampKey
import com.metrolist.music.constants.ListenTogetherSessionTokenKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.ProxyEnabledKey
import com.metrolist.music.constants.ProxyPasswordKey
import com.metrolist.music.constants.ProxyTypeKey
import com.metrolist.music.constants.ProxyUrlKey
import com.metrolist.music.constants.ProxyUsernameKey

object SecretPreferenceKeys {
    val all: Set<Preferences.Key<*>> =
        setOf(
            // The YouTube sign-in cookie: the one secret that survives
            // scrubbing, sealed in CookieVault.
            InnerTubeCookieKey,
            // AI lyrics-translation service credentials. The feature is
            // hidden (SaraswatiFeatures.AI_LYRICS = false), but a restored
            // legacy backup could still carry a live key.
            OpenRouterApiKey,
            DeeplApiKey,
            // Last.fm and Discord were removed outright (owner decision). A legacy backup or datastore may still
            // carry a Last.fm session, or a family member's Discord identity and the presence-button URLs; wiped
            // on every start, never exported or restored.
            LastFMSessionKey,
            LastFMUsernameKey,
            DiscordUsernameKey,
            DiscordNameKey,
            DiscordAvatarKey,
            DiscordButton1UrlKey,
            DiscordButton2UrlKey,
            // Listen Together's reconnection bearer token. The feature
            // stays visible (it's woven through HomeScreen) but, per audit
            // A9.1/NEW-5, must not connect anywhere until Phase 4 adds a
            // join confirmation — so the token isn't exercised today, but
            // it is still a real credential for a remote server and must
            // not survive a restore.
            ListenTogetherSessionTokenKey,
            // Proxy settings (SaraswatiFeatures.PROXY = false): audit
            // A15.2 — a proxy config restored from a legacy backup would
            // route all YouTube traffic through whatever host and
            // credentials it names.
            ProxyEnabledKey,
            ProxyUrlKey,
            ProxyTypeKey,
            ProxyUsernameKey,
            ProxyPasswordKey,
            // Family hub credentials (Phase 3): bearer tokens for the
            // Cloudflare Worker that holds the roster, stations and
            // kill-switches. Sealed at rest like the cookie, because a
            // phone-to-phone transfer or restored backup must never carry
            // another device's admin key or session across.
            FamilyAdminKeyKey,
            FamilyHubSessionTokenKey,
            FamilyHubClaimSecretKey,
        )

    // The subset of [all] this app keeps at rest, sealed by the vault, and
    // therefore the only keys SecretScrubber lets survive (when the stored
    // value really is vault-sealed). Everything else in [all] is wiped on
    // every start. SealedPreferences reads this to decide what to seal and
    // open; SecretKeyCoverageTest-style tests pin that it stays inside [all].
    val VAULT_SEALED: Set<Preferences.Key<*>> =
        setOf(
            InnerTubeCookieKey,
            FamilyAdminKeyKey,
            FamilyHubSessionTokenKey,
            FamilyHubClaimSecretKey,
        )

    // Preference keys the coverage test's name regex flags as
    // secret-shaped, but which are not themselves credentials.
    val NOT_SECRET: Map<Preferences.Key<*>, String> =
        mapOf(
            ListenTogetherSessionTimestampKey to
                ("an epoch-millis Long paired with ListenTogetherSessionTokenKey; carries no " +
                    "credential material by itself and is useless once the token above is scrubbed"),
        )
}

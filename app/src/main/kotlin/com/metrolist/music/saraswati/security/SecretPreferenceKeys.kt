/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: every preference whose value could let someone impersonate
// the family's YouTube account, an AI-translation or Last.fm account, or
// redirect this app's network traffic through an attacker-controlled host,
// if it ever reached a cloud backup or a restored/hand-copied legacy
// datastore file. SecretScrubber wipes every key here on every app start,
// except an InnerTubeCookieKey value the vault actually accepts (i.e. one
// this app itself sealed).
//
// SecretKeyCoverageTest reflects over every Preferences.Key in
// PreferenceKeysKt whose name looks secret-shaped and fails the build if a
// future upstream merge adds a new one without a conscious decision: it
// must land in [all] or in [NOT_SECRET] with a reason.
package com.metrolist.music.saraswati.security

import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMSessionKey
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
            // Last.fm scrobbling session (SaraswatiFeatures.LASTFM = false).
            LastFMSessionKey,
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

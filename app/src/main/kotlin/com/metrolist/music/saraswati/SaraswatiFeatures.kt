/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: feature flags for the family build. Phase 1 drops network-facing
// integrations this fork cannot vet or maintain (Discord presence, Last.fm
// scrobbling, AI lyrics translation, music recognition, alarms, widgets),
// switches off InnerTubeX's remote-cipher-code channel (audit A12-b, High:
// it downloads and runs an unsigned JS program per player from a third-party
// repo) and hides the legacy proxy settings (audit A15.2). Each flag is a
// single source of truth: entry points check it instead of duplicating the
// "is this feature allowed" decision at every call site. Re-enable a flag
// only after adding the feature's hosts to the egress allowlist (Task 12)
// and reviewing what it needs. See docs/strip-checklist.md.
package com.metrolist.music.saraswati

object SaraswatiFeatures {
    const val DISCORD = false
    const val LASTFM = false
    const val AI_LYRICS = false
    const val RECOGNITION = false
    const val ALARMS = false
    const val WIDGETS = false
    const val PROXY = false
    const val REMOTE_CIPHER_CODE = false

    // SARASWATI: registry keys (not display names) for the four lyrics
    // providers this fork keeps. Dropped: KuGou, Paxsenix, LyricsPlus (six
    // community mirrors) and Zemer (which also calls jewishmusic.fm:8443) —
    // each extra lyrics host learns every song the family plays. See
    // docs/strip-checklist.md.
    val ALLOWED_LYRICS_PROVIDER_KEYS: Set<String> = setOf(
        "BetterLyrics",
        "LrcLib",
        "YouTubeSubtitle",
        "YouTube",
    )
}

// SARASWATI: Listen Together stays visible in the UI (it's woven through
// HomeScreen) but must not connect anywhere until Phase 4 adds a join
// confirmation (audit A9.1/NEW-5). Checked at every surface that could
// trigger a join: deep links, shared text, and explicit intents.
object SaraswatiDeepLinkPolicy {
    fun isListenTogetherLink(
        host: String?,
        firstPathSegment: String?,
    ): Boolean =
        host?.equals("listen", ignoreCase = true) == true ||
            firstPathSegment?.equals("listen", ignoreCase = true) == true
}

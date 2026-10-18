/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import com.metrolist.music.saraswati.SaraswatiFeatures

object LyricsProviderRegistry {
    // SARASWATI: filtered to SaraswatiFeatures.ALLOWED_LYRICS_PROVIDER_KEYS.
    // Each extra lyrics host this fork queries would learn every song the
    // family plays. Dropped: KuGou, Paxsenix, LyricsPlus (six community
    // mirrors) and Zemer (also calls jewishmusic.fm:8443). See
    // docs/strip-checklist.md to re-add one later.
    private val providerMap = mapOf(
        "BetterLyrics" to BetterLyricsProvider,
        "Paxsenix" to PaxsenixLyricsProvider,
        "LrcLib" to LrcLibLyricsProvider,
        "KuGou" to KuGouLyricsProvider,
        "LyricsPlus" to LyricsPlusProvider,
        "Zemer" to ZemerLyricsProvider,
        "YouTubeSubtitle" to YouTubeSubtitleLyricsProvider,
        "YouTube" to YouTubeLyricsProvider,
    ).filterKeys { it in SaraswatiFeatures.ALLOWED_LYRICS_PROVIDER_KEYS }

    val providerNames = providerMap.keys.toList()

    fun getProviderByName(name: String): LyricsProvider? = providerMap[name]

    fun getProviderName(provider: LyricsProvider): String? =
        providerMap.entries.find { it.value == provider }?.key

    fun deserializeProviderOrder(orderString: String): List<String> {
        if (orderString.isBlank()) {
            return getDefaultProviderOrder()
        }
        val saved = orderString.split(",").map { it.trim() }.filter { it in providerNames }
        return saved + getDefaultProviderOrder().filter { it !in saved }
    }

    fun serializeProviderOrder(providers: List<String>): String {
        return providers.filter { it in providerNames }.joinToString(",")
    }

    fun getDefaultProviderOrder(): List<String> = listOf(
        "BetterLyrics",
        "LrcLib",
        "KuGou",
        "Paxsenix",
        "LyricsPlus",
        "Zemer",
        "YouTubeSubtitle",
        "YouTube",
        // SARASWATI: filtered to the allowed four so a blank saved order
        // (deserializeProviderOrder's blank-string branch returns this list
        // verbatim) never re-surfaces a dropped provider.
    ).filter { it in SaraswatiFeatures.ALLOWED_LYRICS_PROVIDER_KEYS }

    fun getOrderedProviders(orderString: String): List<LyricsProvider> {
        val order = deserializeProviderOrder(orderString)
        return order.mapNotNull { getProviderByName(it) }
    }
}

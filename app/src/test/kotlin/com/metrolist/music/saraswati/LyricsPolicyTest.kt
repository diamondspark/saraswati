/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression test for the lyrics-provider allowlist (audit:
// each extra lyrics host learns every song the family plays). Fails if a
// dropped provider (KuGou, Paxsenix, LyricsPlus, Zemer) ever resurfaces.
package com.metrolist.music.saraswati

import com.metrolist.music.lyrics.LyricsProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsPolicyTest {
    @Test
    fun `only the four allowed providers are registered`() {
        assertEquals(
            setOf("BetterLyrics", "LrcLib", "YouTubeSubtitle", "YouTube"),
            LyricsProviderRegistry.providerNames.toSet(),
        )
    }

    @Test
    fun `default provider order resolves to exactly the four allowed providers`() {
        assertEquals(
            4,
            LyricsProviderRegistry.getDefaultProviderOrder()
                .mapNotNull { LyricsProviderRegistry.getProviderByName(it) }
                .size,
        )
    }

    @Test
    fun `a saved order naming dropped providers never restores them`() {
        assertEquals(
            emptyList<String>(),
            LyricsProviderRegistry.deserializeProviderOrder("Kugou,Zemer,Paxsenix")
                .filter { it in listOf("KuGou", "Zemer", "Paxsenix") },
        )
    }
}

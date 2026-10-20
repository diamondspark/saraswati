/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: final fix wave (M1) — the Parivaar member name is the Sangat display name.
package com.metrolist.music.saraswati.family.parivaar

import org.junit.Assert.assertEquals
import org.junit.Test

class ParivaarMemberNameTest {
    @Test
    fun `the Sangat name is used, trimmed`() {
        assertEquals("Mummy", ParivaarMemberName.of("  Mummy ", "SM-A515F"))
    }

    @Test
    fun `the phone model is only the fallback for no name or a blank one`() {
        assertEquals("SM-A515F", ParivaarMemberName.of(null, "SM-A515F"))
        assertEquals("SM-A515F", ParivaarMemberName.of("", "SM-A515F"))
        assertEquals("SM-A515F", ParivaarMemberName.of("   ", "SM-A515F"))
    }

    @Test
    fun `the playlist title carries the name`() {
        assertEquals("Saraswati · Mummy top songs", TopSongsPlaylistSync.title(ParivaarMemberName.of("Mummy", "SM-A515F")))
    }
}

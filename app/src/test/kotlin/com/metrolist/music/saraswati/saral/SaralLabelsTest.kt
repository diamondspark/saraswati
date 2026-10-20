/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the four starting stations are named in English on the
// hub (the owner's editor and the car show those). Saral shows the approved
// Hindi names for them, but only while the owner has not renamed them.
package com.metrolist.music.saraswati.saral

import com.metrolist.music.R
import com.metrolist.music.saraswati.family.FamilyViewModel
import com.metrolist.music.saraswati.family.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SaralLabelsTest {
    private val defaults = FamilyViewModel.DEFAULT_STATIONS

    @Test
    fun `each starting station maps to its approved Hindi name`() {
        assertEquals(R.string.saral_station_bollywood, SaralLabels.hindiLabelFor(defaults[0]))
        assertEquals(R.string.saral_station_bhajan, SaralLabels.hindiLabelFor(defaults[1]))
        assertEquals(R.string.saral_station_ghazal, SaralLabels.hindiLabelFor(defaults[2]))
        assertEquals(R.string.saral_station_meditation, SaralLabels.hindiLabelFor(defaults[3]))
    }

    @Test
    fun `a starting station the owner has filled in with a video still counts`() {
        assertNotNull(SaralLabels.hindiLabelFor(defaults[0].copy(seedVideoId = "dQw4w9WgXcQ", iconIndex = 3)))
    }

    @Test
    fun `a renamed starting station keeps the owner's own name`() {
        assertNull(SaralLabels.hindiLabelFor(defaults[1].copy(title = "Mummy's Bhajans")))
    }

    @Test
    fun `a station the owner added has no Hindi label`() {
        assertNull(SaralLabels.hindiLabelFor(Station("new-1", "Bollywood Classics", "dQw4w9WgXcQ", 0)))
    }
}

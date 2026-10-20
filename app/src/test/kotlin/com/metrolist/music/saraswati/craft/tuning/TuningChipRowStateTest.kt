/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.tuning

import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningChipRowStateTest {
    private fun chip(label: String, selected: Boolean = false) =
        TuningChip(label = label, endpoint = WatchEndpoint(videoId = label), selected = selected)

    @Test
    fun `the row is hidden with no chips`() {
        val state = TuningChipRowState(MutableStateFlow(emptyList()), isListenTogetherGuest = false) { TuneResult.Tuned }

        assertTrue(state.visibleOf(state.chips.value).isEmpty())
    }

    @Test
    fun `labels show exactly as YouTube sends them`() {
        val sent = listOf("Chill · Mix", "Bhajan & Aarti (Morning)", "Jazz 'n' Blues", "Ek Din")
        val state =
            TuningChipRowState(
                MutableStateFlow(sent.map { chip(it) }),
                isListenTogetherGuest = false,
            ) { TuneResult.Tuned }

        assertEquals(sent, state.visibleOf(state.chips.value).map { it.label })
    }

    @Test
    fun `tapping a chip calls select`() = runBlocking {
        val tapped = mutableListOf<TuningChip>()
        val state =
            TuningChipRowState(MutableStateFlow(emptyList()), isListenTogetherGuest = false) {
                tapped += it
                TuneResult.Tuned
            }
        val target = chip("Chill")

        assertEquals(TuneResult.Tuned, state.tap(target))

        assertEquals(listOf(target), tapped)
    }

    @Test
    fun `a Listen Together guest sees no row`() {
        val state =
            TuningChipRowState(
                MutableStateFlow(listOf(chip("Chill", selected = true))),
                isListenTogetherGuest = true,
            ) { TuneResult.Tuned }

        assertTrue(state.visibleOf(state.chips.value).isEmpty())
    }
}

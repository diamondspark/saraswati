/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 8 — the Home card's state: play, the card hidden without the current window's mix, and the
// title following the window.
package com.metrolist.music.saraswati.craft.prahar

import com.metrolist.music.saraswati.family.parivaar.MixTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PraharViewModelTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val now = MutableStateFlow(LocalDateTime.parse("2026-10-19T07:00"))
    private val mixes = MutableStateFlow<PraharMix?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun viewModel() = PraharViewModel(mixes, now, { PraharQueue(it) }, scope)

    private fun mix(prahar: Prahar) =
        PraharMix(prahar, day, 1L, listOf(MixTrack("abcdefghijk", "Song", "Artist", null, 1000L)))

    @Test
    fun `play starts a PraharQueue`() {
        mixes.value = mix(Prahar.PRATAH)
        val started = mutableListOf<PraharQueue>()
        viewModel().play { started += it }
        assertEquals(1, started.size)
        assertEquals(Prahar.PRATAH, started.single().mix.prahar)
    }

    @Test
    fun `the card is hidden without a mix for the current window`() {
        val vm = viewModel()
        assertNull(vm.mix.value)
        mixes.value = mix(Prahar.SANDHYA)
        assertNull(vm.mix.value)
        mixes.value = mix(Prahar.PRATAH)
        assertNotNull(vm.mix.value)
    }

    @Test
    fun `the title follows the window`() {
        val vm = viewModel()
        assertEquals(Prahar.PRATAH, vm.window.value)
        now.value = LocalDateTime.parse("2026-10-19T19:00")
        assertEquals(Prahar.SANDHYA, vm.window.value)
    }
}

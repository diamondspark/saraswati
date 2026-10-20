/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 review fix (3) — a mix song the owner already has is downloaded through its stored row, so the
// download does not overwrite that row's album link and library tokens with the mix's metadata.
package com.metrolist.music.saraswati.family.parivaar

import org.junit.Assert.assertEquals
import org.junit.Test

class MixDownloaderTest {
    private val songCalls = mutableListOf<String>()
    private val newCalls = mutableListOf<String>()

    private fun enqueue(
        track: MixTrack,
        known: Set<String>,
    ) = enqueueMixTrack(track, songExists = { it in known }, downloadSong = { songCalls += it }, downloadNew = { newCalls += it.id })

    @Test
    fun `a song the phone already has goes through its stored row`() {
        enqueue(track("known000001"), known = setOf("known000001"))

        assertEquals(listOf("known000001"), songCalls)
        assertEquals(emptyList<String>(), newCalls)
    }

    @Test
    fun `a song the phone has never seen is built from the mix metadata`() {
        enqueue(track("fresh000001"), known = emptySet())

        assertEquals(emptyList<String>(), songCalls)
        assertEquals(listOf("fresh000001"), newCalls)
    }
}

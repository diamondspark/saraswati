/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 6 — the seed, the window filter and the interleaving of the Prahar mix.
package com.metrolist.music.saraswati.craft.prahar

import com.metrolist.music.saraswati.family.parivaar.MixTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PraharMixBuilderTest {
    private val morning = Prahar.PRATAH

    private fun play(
        songId: String,
        at: String,
        ms: Long = 1_000L,
    ) = PraharPlay(songId, LocalDateTime.parse(at), ms)

    private fun track(id: String) = MixTrack(id, "title $id", "artist", null, null)

    @Test
    fun `only plays inside this window's hours count`() {
        val plays =
            listOf(
                play("a", "2026-10-01T07:00"),
                play("b", "2026-10-01T10:00"),
                play("c", "2026-10-01T05:59"),
            )
        assertEquals(listOf("a"), PraharMixBuilder.ownSongs(plays, morning, emptySet()))
    }

    @Test
    fun `a song played on more days ranks above one played more times on one day`() {
        val plays =
            listOf(
                play("oneDayMany", "2026-10-01T07:00", 1_000L),
                play("oneDayMany", "2026-10-01T07:10", 1_000L),
                play("oneDayMany", "2026-10-01T07:20", 1_000L),
                play("oneDayMany", "2026-10-01T07:30", 1_000L),
                play("twoDays", "2026-10-01T07:00", 1L),
                play("twoDays", "2026-10-02T07:00", 1L),
            )
        assertEquals(listOf("twoDays", "oneDayMany"), PraharMixBuilder.ownSongs(plays, morning, emptySet()))
    }

    @Test
    fun `snoozed songs never appear`() {
        val plays = listOf(play("a", "2026-10-01T07:00"), play("b", "2026-10-01T07:00"))
        assertEquals(listOf("b"), PraharMixBuilder.ownSongs(plays, morning, setOf("a")))
    }

    @Test
    fun `local songs never seed a mix`() {
        val plays = listOf(play("local:x", "2026-10-01T07:00"), play("yt", "2026-10-01T07:00"))
        assertEquals(listOf("yt"), PraharMixBuilder.ownSongs(plays, morning, emptySet()))
    }

    @Test
    fun `fewer than 5 own songs gives an empty mix`() {
        val own = (1..4).map { track("o$it") }
        val radio = (1..10).map { track("r$it") }
        assertTrue(PraharMixBuilder.build(own, radio, emptySet()).isEmpty())
    }

    @Test
    fun `the seed rotates daily among the top three`() {
        val own = listOf("a", "b", "c", "d", "e")
        val seeds = (0..2).map { PraharMixBuilder.radioSeed(own, LocalDate.of(2026, 1, 1).plusDays(it.toLong())) }
        assertEquals(3, seeds.toSet().size)
        assertTrue(seeds.all { it in listOf("a", "b", "c") })
        assertNull(PraharMixBuilder.radioSeed(emptyList(), LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `the mix alternates one own song with two radio songs and never exceeds 25`() {
        val own = (1..10).map { track("o$it") }
        val radio = (1..30).map { track("r$it") }
        val mix = PraharMixBuilder.build(own, radio, emptySet())
        assertEquals(25, mix.size)
        assertEquals(
            listOf("o1", "r1", "r2", "o2", "r3", "r4", "o3"),
            mix.take(7).map { it.videoId },
        )
    }

    @Test
    fun `snoozed songs and duplicates are dropped from the mix`() {
        val own = (1..5).map { track("o$it") }
        val radio = listOf(track("o2"), track("r1"), track("r1"), track("s"))
        val mix = PraharMixBuilder.build(own, radio, setOf("s"))
        assertEquals(listOf("o1", "r1", "o2", "o3", "o4", "o5"), mix.map { it.videoId })
    }

    @Test
    fun `same input gives the same output`() {
        val own = (1..6).map { track("o$it") }
        val radio = (1..12).map { track("r$it") }
        assertEquals(PraharMixBuilder.build(own, radio, emptySet()), PraharMixBuilder.build(own, radio, emptySet()))
    }
}

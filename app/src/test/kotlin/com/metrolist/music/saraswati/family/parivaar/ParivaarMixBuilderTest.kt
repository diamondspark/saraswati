/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 11 — the pinned Parivaar Mix algorithm: shared songs first, then the members
// take turns, then radio fills the tail.
package com.metrolist.music.saraswati.family.parivaar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParivaarMixBuilderTest {
    private fun track(id: String) = MixTrack(id, "Title $id", "Artist", null, null)

    private fun member(
        deviceId: String,
        vararg ids: String,
    ) = MemberTopSongs(deviceId, "Member $deviceId", ids.map(::track))

    private fun ids(tracks: List<MixTrack>) = tracks.map { it.videoId }

    @Test
    fun `songs shared by more members come first`() {
        val a = member("a", "a1", "two", "three", "a4")
        val b = member("b", "b1", "three", "two", "b4")
        val c = member("c", "c1", "three", "c3", "c4")

        val mix = ParivaarMixBuilder.build(listOf(a, b, c), emptyList())

        // three: 3 members. two: 2 members. Then the turns.
        assertEquals(listOf("three", "two"), ids(mix).take(2))
    }

    @Test
    fun `members count ties break by rank sum then video id`() {
        val a = member("a", "x1", "y1", "z1")
        val b = member("b", "z1", "y1", "x1")
        val c = member("c", "q1", "q2", "q3")

        val mix = ParivaarMixBuilder.build(listOf(a, b, c), emptyList())

        // x1: ranks 0+2, y1: 1+1, z1: 2+0 — all sums equal 2, so the video id decides.
        assertEquals(listOf("x1", "y1", "z1"), ids(mix).take(3))
        assertEquals("x1", ParivaarMixBuilder.radioSeed(listOf(a, b, c)))
    }

    @Test
    fun `a lower rank sum wins among songs shared by the same number of members`() {
        val a = member("a", "late", "early")
        val b = member("b", "other", "late", "early")

        val mix = ParivaarMixBuilder.build(listOf(a, b), emptyList())

        // early: 1+2 = 3. late: 0+1 = 1.
        assertEquals(listOf("late", "early"), ids(mix).take(2))
    }

    @Test
    fun `remaining songs alternate between members in turns`() {
        val b = member("b", "b1", "b2", "b3")
        val a = member("a", "a1", "a2", "a3")

        val mix = ParivaarMixBuilder.build(listOf(b, a), emptyList())

        // Members take turns in device id order, whatever order they were passed in.
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3", "b3"), ids(mix))
    }

    @Test
    fun `a turn skips songs already in the mix and a short list drops out`() {
        val a = member("a", "shared", "a2", "a3", "a4")
        val b = member("b", "shared", "b2")

        val mix = ParivaarMixBuilder.build(listOf(a, b), emptyList())

        assertEquals(listOf("shared", "a2", "b2", "a3", "a4"), ids(mix))
    }

    @Test
    fun `the overlap counts toward the forty before radio`() {
        val a = member("a", *(1..30).map { "a%02d".format(it) }.toTypedArray())
        val b = member("b", *(1..30).map { "b%02d".format(it) }.toTypedArray())
        val radio = (1..30).map { track("r%02d".format(it)) }

        val mix = ParivaarMixBuilder.build(listOf(a, b), radio)

        assertEquals(50, mix.size)
        assertEquals(40, mix.count { !it.videoId.startsWith("r") })
    }

    @Test
    fun `radio fills the tail without duplicates`() {
        val a = member("a", "shared", "a2")
        val b = member("b", "shared", "b2")
        val radio = listOf(track("a2"), track("r1"), track("r1"), track("shared"), track("r2"))

        val mix = ParivaarMixBuilder.build(listOf(a, b), radio)

        assertEquals(listOf("shared", "a2", "b2", "r1", "r2"), ids(mix))
        assertEquals(mix.size, ids(mix).toSet().size)
    }

    @Test
    fun `never exceeds 50 tracks and radio adds at most 10`() {
        val a = member("a", *(1..60).map { "a%02d".format(it) }.toTypedArray())
        val b = member("b", *(1..60).map { "b%02d".format(it) }.toTypedArray())
        val manyRadio = (1..100).map { track("r%03d".format(it)) }

        val full = ParivaarMixBuilder.build(listOf(a, b), manyRadio)
        assertEquals(ParivaarMixBuilder.MIX_MAX, full.size)
        assertEquals(ParivaarMixBuilder.RADIO_MAX, full.count { it.videoId.startsWith("r") })

        // Even when the members supply little, radio is capped at ten.
        val small = ParivaarMixBuilder.build(listOf(member("a", "s", "a2"), member("b", "s", "b2")), manyRadio)
        assertEquals(3 + ParivaarMixBuilder.RADIO_MAX, small.size)
    }

    @Test
    fun `an overlap larger than forty is cut at forty`() {
        val shared = (1..45).map { "s%02d".format(it) }.toTypedArray()

        val mix = ParivaarMixBuilder.build(listOf(member("a", *shared), member("b", *shared)), emptyList())

        assertEquals(ParivaarMixBuilder.MIX_MAX - ParivaarMixBuilder.RADIO_MAX, mix.size)
    }

    @Test
    fun `fewer than two members gives an empty mix`() {
        val a = member("a", "a1", "a2")
        val radio = listOf(track("r1"))

        assertTrue(ParivaarMixBuilder.build(emptyList(), radio).isEmpty())
        assertTrue(ParivaarMixBuilder.build(listOf(a), radio).isEmpty())
        // A member with nothing to offer is not a member.
        assertTrue(ParivaarMixBuilder.build(listOf(a, member("b")), radio).isEmpty())
        assertNull(ParivaarMixBuilder.radioSeed(listOf(a, member("b"))))
    }

    @Test
    fun `no overlap gives no radio seed`() {
        val a = member("a", "a1", "a2")
        val b = member("b", "b1", "b2")

        assertNull(ParivaarMixBuilder.radioSeed(listOf(a, b)))
        assertEquals(listOf("a1", "b1", "a2", "b2"), ids(ParivaarMixBuilder.build(listOf(a, b), emptyList())))
    }

    @Test
    fun `a song repeated inside one list does not count as shared`() {
        val a = member("a", "dup", "dup", "a3")
        val b = member("b", "b1")

        assertNull(ParivaarMixBuilder.radioSeed(listOf(a, b)))
        assertEquals(listOf("dup", "b1", "a3"), ids(ParivaarMixBuilder.build(listOf(a, b), emptyList())))
    }

    @Test
    fun `the seed is the first shared song`() {
        val a = member("a", "a1", "hit", "a3")
        val b = member("b", "b1", "hit")

        assertEquals("hit", ParivaarMixBuilder.radioSeed(listOf(a, b)))
    }

    @Test
    fun `same input gives the same output`() {
        val a = member("a", "x", "y", "a3")
        val b = member("b", "y", "x", "b3")
        val c = member("c", "c1", "y")
        val radio = listOf(track("r1"), track("r2"))

        val first = ParivaarMixBuilder.build(listOf(a, b, c), radio)
        val second = ParivaarMixBuilder.build(listOf(c, a, b), radio)

        assertEquals(first, second)
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 14 — the recap's rules on hand-built snapshots.
package com.metrolist.music.saraswati.family.recap

import com.metrolist.music.saraswati.family.parivaar.MemberTopSongs
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FamilyRecapBuilderTest {
    private val today = LocalDate.of(2026, 10, 19)
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun track(id: String) = MixTrack(id, "Title $id", "Artist", null, null)

    private fun member(
        deviceId: String,
        name: String,
        vararg ids: String,
    ) = MemberTopSongs(deviceId, name, ids.map(::track))

    private fun snapshot(
        takenAt: Long,
        vararg members: MemberTopSongs,
    ) = RecapSnapshot(takenAt, members.toList())

    @Test
    fun `the anthem is the song on the most lists`() {
        val current =
            snapshot(
                now,
                member("me", "Mohit", "a", "b"),
                member("mum", "Mummy", "c", "b"),
                member("dad", "Papa", "d", "b", "a"),
            )

        val recap = FamilyRecapBuilder.build(current, null, "me", today)!!

        assertEquals("b", recap.anthem!!.videoId)
        assertEquals(3, recap.anthemMembers)
    }

    @Test
    fun `no song on two lists gives no anthem`() {
        val current = snapshot(now, member("me", "Mohit", "a"), member("mum", "Mummy", "b"))

        val recap = FamilyRecapBuilder.build(current, null, "me", today)!!

        assertNull(recap.anthem)
        assertEquals(0, recap.anthemMembers)
    }

    @Test
    fun `each member's favourite is their rank-one song`() {
        val current =
            snapshot(
                now,
                member("mum", "Mummy", "m1", "m2"),
                member("me", "Mohit", "a1", "a2"),
                member("dad", "Papa", "p1"),
            )

        val recap = FamilyRecapBuilder.build(current, null, "me", today)!!

        assertEquals(listOf("Mohit", "Mummy", "Papa"), recap.favourites.map { it.memberName })
        assertEquals(listOf("a1", "m1", "p1"), recap.favourites.map { it.track.videoId })
    }

    @Test
    fun `shared counts compare only with this phone's list`() {
        val current =
            snapshot(
                now,
                member("me", "Mohit", "x", "y"),
                member("mum", "Mummy", "x", "z", "w"),
                member("dad", "Papa", "q", "w"),
                member("nan", "Nana", "r"),
            )

        val recap = FamilyRecapBuilder.build(current, null, "me", today)!!

        assertEquals(listOf(SharedCount("Mummy", 1)), recap.sharedWithMe)
    }

    @Test
    fun `new this week is empty without a previous snapshot and lists each member's new songs`() {
        val current = snapshot(now, member("me", "Mohit", "n1", "n2", "n3", "n4", "old"), member("mum", "Mummy", "m1", "m2"))
        val previous = snapshot(now - 7 * day, member("me", "Mohit", "old"), member("mum", "Mummy", "m1"))

        assertTrue(FamilyRecapBuilder.build(current, null, "me", today)!!.newThisWeek.isEmpty())

        val recap = FamilyRecapBuilder.build(current, previous, "me", today)!!

        assertEquals(listOf("n1", "n2", "n3"), recap.newThisWeek.first { it.memberName == "Mohit" }.tracks.map { it.videoId })
        assertEquals(listOf("m2"), recap.newThisWeek.first { it.memberName == "Mummy" }.tracks.map { it.videoId })
    }

    @Test
    fun `fewer than two members gives no recap`() {
        assertNull(FamilyRecapBuilder.build(snapshot(now, member("me", "Mohit", "a")), null, "me", today))
        assertNull(FamilyRecapBuilder.build(snapshot(now, member("me", "Mohit", "a"), member("mum", "Mummy")), null, "me", today))
        assertNull(FamilyRecapBuilder.build(snapshot(now, member("me", "Mohit"), member("mum", "Mummy")), null, "me", today))
    }

    @Test
    fun `a member who left the registry disappears`() {
        val before = snapshot(now - 7 * day, member("me", "Mohit", "a"), member("mum", "Mummy", "a"), member("dad", "Papa", "p"))
        val current = snapshot(now, member("me", "Mohit", "a"), member("mum", "Mummy", "a"))

        val recap = FamilyRecapBuilder.build(current, before, "me", today)!!

        assertEquals(listOf("Mohit", "Mummy"), recap.favourites.map { it.memberName })
        assertTrue(recap.newThisWeek.none { it.memberName == "Papa" })
        assertTrue(recap.sharedWithMe.none { it.memberName == "Papa" })
    }
}

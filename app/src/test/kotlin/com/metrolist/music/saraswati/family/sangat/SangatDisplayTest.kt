/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 fix wave (Task 19 mockup conformance) — the display rules behind the Sangat screens: who a song
// is "added by", the live rows and Home banner, the progress clock, and the room info read from the room state.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.listentogether.RoomState
import com.metrolist.music.listentogether.TrackInfo
import com.metrolist.music.listentogether.UserInfo
import com.metrolist.music.saraswati.family.LiveSangatRoom
import com.metrolist.music.saraswati.family.LiveSangatSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SangatDisplayTest {
    private fun live(
        code: String,
        host: String = "Papa",
        car: Boolean = false,
    ) = LiveSangatRoom(code, host, "dev-$code", car, memberCount = 3, startedAt = "2026-10-19T10:00:00Z")

    private fun info(
        code: String,
        isHost: Boolean,
        vararg members: SangatMember,
    ) = SangatRoomInfo(code, "Papa", isHost, members.toList(), null, false, 0)

    @Test
    fun `added by names the adder, you for this phone, and the host when blank`() {
        assertEquals(Adder("Papa", false), SangatDisplay.addedBy("", "Papa", "Asha"))
        assertEquals(Adder("Dadi", false), SangatDisplay.addedBy(" Dadi ", "Papa", "Asha"))
        assertEquals(Adder("asha", true), SangatDisplay.addedBy("asha", "Papa", " Asha "))
        assertEquals(Adder("Ghost", false), SangatDisplay.addedBy("Ghost", "Papa", ""))
    }

    @Test
    fun `clock and progress match the mockup's m ss row`() {
        assertEquals("1:48", SangatDisplay.clock(108_000))
        assertEquals("4:12", SangatDisplay.clock(252_000))
        assertEquals("0:00", SangatDisplay.clock(-5))
        assertEquals(0.4286f, SangatDisplay.progress(108_000, 252_000), 0.001f)
        assertEquals(0f, SangatDisplay.progress(5, 0), 0f)
        assertEquals(1f, SangatDisplay.progress(999_000, 252_000), 0f)
    }

    @Test
    fun `initials and colours are stable and safe for odd names`() {
        assertEquals("P", SangatDisplay.initial("papa"))
        assertEquals("?", SangatDisplay.initial("  "))
        assertEquals("A", SangatDisplay.initial("  ~Asha"))
        assertEquals(SangatDisplay.colorIndex("Papa"), SangatDisplay.colorIndex(" papa "))
        assertTrue(SangatDisplay.colorIndex("Anything at all") in 0..3)
    }

    @Test
    fun `a hosted room is left out of the live list and a joined one is marked`() {
        val rows = SangatDisplay.rows(listOf(live("MINE"), live("NANA", "Nana")), info("MINE", true), false)
        assertEquals(listOf("NANA"), rows.map { it.room.roomCode })
        assertFalse(rows.single().joined)

        val joined = SangatDisplay.rows(listOf(live("NANA", "Nana")), info("NANA", false), false)
        assertTrue(joined.single().joined)
    }

    @Test
    fun `a joined room the hub list does not show yet still gets a row with Leave`() {
        val rows = SangatDisplay.rows(emptyList(), info("CAR1", false, SangatMember("Papa", false), SangatMember("Me", true)), true)
        val row = rows.single()
        assertTrue(row.joined)
        assertTrue(row.room.carMode)
        assertEquals("Papa", row.room.hostName)
        assertEquals(2, row.room.memberCount)
    }

    @Test
    fun `banner is null while hosting, a Join for the first live room, a Leave when joined`() {
        assertNull(SangatDisplay.banner(listOf(live("A")), info("MINE", true), false))
        assertNull(SangatDisplay.banner(emptyList(), null, false))
        assertEquals(false, SangatDisplay.banner(listOf(live("A")), null, false)?.joined)
        val joined =
            SangatDisplay.banner(
                listOf(live("A", "Nana")),
                info("A", false, SangatMember("Nana", false), SangatMember("Dadi", false), SangatMember("Me", true)),
                false,
            )!!
        assertTrue(joined.joined)
        assertEquals(listOf("Nana", "Dadi"), joined.syncedWith)
    }

    @Test
    fun `banner is remote only when joined to a car room or a remote controller`() {
        val me = SangatMember("Me", true)
        val others = arrayOf(SangatMember("Papa", false), me)
        assertTrue(SangatDisplay.banner(listOf(live("CAR", car = true)), info("CAR", false, *others), false)!!.remote)
        assertFalse(SangatDisplay.banner(listOf(live("HOME")), info("HOME", false, *others), false)!!.remote)
        // The moment between a car mark and the next poll: the hub's list still says home, the controller flag is set.
        assertTrue(SangatDisplay.banner(listOf(live("HOME")), info("HOME", false, *others), true)!!.remote)
        assertFalse(SangatDisplay.banner(listOf(live("CAR", car = true)), null, true)!!.remote)
    }

    @Test
    fun `room info reads host, connected members, track and position from the room state`() {
        val track = TrackInfo(id = "t1", title = "Pal Pal", artist = "Kishore", duration = 252_000, suggestedBy = "Papa")
        val state =
            RoomState(
                roomCode = "CAR1",
                hostId = "u-host",
                users =
                    listOf(
                        UserInfo("u-dadi", "Dadi", isHost = false),
                        UserInfo("u-host", "Papa", isHost = true),
                        UserInfo("u-gone", "Gone", isHost = false, isConnected = false),
                        UserInfo("u-me", "Asha", isHost = false),
                    ),
                currentTrack = track,
                isPlaying = true,
                position = 108_000,
                lastUpdate = 1L,
            )

        val info = SangatRoomInfo.from(state, "u-me")!!

        assertEquals("Papa", info.hostName)
        assertFalse(info.isHost)
        assertEquals(listOf("Papa", "Dadi", "Asha"), info.members.map { it.name })
        assertEquals(3, info.listening)
        assertEquals(listOf("Papa", "Dadi"), info.others)
        assertEquals("t1", info.track?.id)
        assertEquals(108_000L, info.positionMs)
        assertTrue(SangatRoomInfo.from(state, "u-host")!!.isHost)
        assertNull(SangatRoomInfo.from(null, "u-me"))
    }

    @Test
    fun `a live room shows its song title, shortened and on one line, or nothing`() {
        assertNull(SangatDisplay.songTitle(live("A")))
        assertNull(SangatDisplay.songTitle(live("A").copy(nowPlaying = LiveSangatSong("   "))))
        assertEquals(
            "Pal Pal Dil Ke Paas",
            SangatDisplay.songTitle(live("A").copy(nowPlaying = LiveSangatSong(" Pal Pal\nDil Ke  Paas ", "Kishore Kumar"))),
        )
        val long = SangatDisplay.songTitle(live("A").copy(nowPlaying = LiveSangatSong("x".repeat(200))))!!
        assertEquals(60, long.length)
        assertTrue(long.endsWith("…"))
    }
}

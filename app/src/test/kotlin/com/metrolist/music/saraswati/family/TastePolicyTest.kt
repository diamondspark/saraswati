/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 13 — whose taste a play shapes: solo plays and my own Sangat picks count, a
// passenger's pick, the host's pick and the Parivaar Mix do not unless the toggle says so.
package com.metrolist.music.saraswati.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TastePolicyTest {
    private fun resolve(
        inRoom: Boolean = false,
        isHost: Boolean = false,
        me: String = "mohit",
        suggestedBy: String? = null,
        mix: Boolean = false,
    ) = TastePolicy.resolve(inRoom, isHost, me, suggestedBy, mix)

    // Truth table of the verdict a play gets, so a hard-coded `counts` or `mix` cannot pass
    private fun verdict(
        toggle: Boolean = false,
        inRoom: Boolean = false,
        isHost: Boolean = false,
        suggestedBy: String? = null,
        mix: Boolean = false,
    ) = TastePolicy.counts(resolve(inRoom, isHost, suggestedBy = suggestedBy, mix = mix), toggle)

    private fun counts(
        context: PlayContext,
        toggle: Boolean = false,
    ) = TastePolicy.counts(context, toggle)

    @Test fun `solo plays count`() {
        assertEquals(PlayContext.Solo, resolve())
        assertTrue(counts(resolve()))
    }

    @Test fun `host - a track with empty suggestedBy counts`() {
        assertTrue(counts(resolve(inRoom = true, isHost = true, suggestedBy = null)))
        assertTrue(counts(resolve(inRoom = true, isHost = true, suggestedBy = "")))
    }

    @Test fun `host - a passenger's pick does not count`() {
        assertFalse(counts(resolve(inRoom = true, isHost = true, suggestedBy = "papa")))
    }

    @Test fun `guest - the host's pick does not count`() {
        assertFalse(counts(resolve(inRoom = true, isHost = false, suggestedBy = null)))
        assertFalse(counts(resolve(inRoom = true, isHost = false, suggestedBy = "")))
    }

    @Test fun `guest - my own pick counts`() {
        assertTrue(counts(resolve(inRoom = true, isHost = false, suggestedBy = "mohit")))
        assertTrue(counts(resolve(inRoom = true, isHost = false, suggestedBy = " Mohit ")))
    }

    @Test fun `a guest with no name never owns a pick`() {
        assertFalse(counts(resolve(inRoom = true, isHost = false, me = "", suggestedBy = "")))
        assertFalse(counts(resolve(inRoom = true, isHost = false, me = "  ", suggestedBy = "  ")))
    }

    @Test fun `Parivaar Mix plays do not count`() {
        assertEquals(PlayContext.ParivaarMix, resolve(mix = true))
        assertFalse(counts(resolve(mix = true)))
    }

    @Test fun `the toggle makes Sangat and Parivaar Mix plays count`() {
        assertTrue(counts(resolve(mix = true), toggle = true))
        assertTrue(counts(resolve(inRoom = true, isHost = true, suggestedBy = "papa"), toggle = true))
        assertTrue(counts(resolve(inRoom = true, isHost = false, suggestedBy = null), toggle = true))
    }

    @Test fun `a Parivaar Mix track stays the mix's even while hosting or joining a Sangat`() {
        assertEquals(PlayContext.ParivaarMix, resolve(inRoom = true, isHost = true, mix = true))
        assertEquals(PlayContext.ParivaarMix, resolve(inRoom = true, isHost = false, mix = true))
        assertFalse(verdict(inRoom = true, isHost = true, mix = true))
        assertTrue(verdict(toggle = true, inRoom = true, isHost = true, mix = true))
    }

    @Test fun `a passenger's own pick in a room still counts as theirs, flagged or not`() {
        assertEquals(PlayContext.Sangat(addedBySelf = true), resolve(inRoom = true, isHost = false, suggestedBy = "mohit", mix = true))
        assertTrue(verdict(inRoom = true, isHost = false, suggestedBy = "mohit", mix = true))
        assertFalse(verdict(inRoom = true, isHost = true, suggestedBy = "papa", mix = true))
    }

    @Test fun `leaving a Sangat mid-song keeps a passenger's pick out of the owner's taste`() {
        assertEquals(PlayContext.Sangat(addedBySelf = false), resolve(inRoom = false, suggestedBy = "papa"))
        assertFalse(verdict(inRoom = false, suggestedBy = "papa"))
        assertTrue(verdict(toggle = true, inRoom = false, suggestedBy = "papa"))
        assertTrue("my own pick, after leaving, is still mine", verdict(inRoom = false, suggestedBy = "Mohit"))
        assertEquals(PlayContext.Solo, resolve(inRoom = false, suggestedBy = ""))
    }

    @Test fun `the verdict table - a hard-coded counts or mix cannot pass`() {
        assertTrue(verdict())
        assertFalse(verdict(mix = true))
        assertTrue(verdict(toggle = true, mix = true))
        assertFalse(verdict(inRoom = true, isHost = false))
        assertTrue(verdict(inRoom = true, isHost = true))
        assertFalse(verdict(inRoom = true, isHost = true, suggestedBy = "papa"))
        assertTrue(verdict(toggle = true, inRoom = true, isHost = true, suggestedBy = "papa"))
        assertFalse(verdict(inRoom = false, mix = true, suggestedBy = null))
    }

    // ---- onPlaybackStatsReady's decision, extracted so it can run without a service ----

    private val thirty = 30_000L

    @Test fun `a non-counting play writes no Event row`() {
        val plan = PlaybackStatsPlan.of(60_000, thirty, historyPaused = false, counts = false)
        assertFalse(plan.writeLocalEvent)
        assertFalse(plan.registerWithYouTube)
    }

    @Test fun `a counting play past the threshold writes the row and registers`() {
        val plan = PlaybackStatsPlan.of(30_000, thirty, historyPaused = false, counts = true)
        assertTrue(plan.writeLocalEvent)
        assertTrue(plan.registerWithYouTube)
    }

    @Test fun `a short play does nothing`() {
        val plan = PlaybackStatsPlan.of(29_999, thirty, historyPaused = false, counts = true)
        assertFalse(plan.writeLocalEvent)
        assertFalse(plan.registerWithYouTube)
    }

    @Test fun `paused history stops the local row but still registers as upstream did`() {
        val plan = PlaybackStatsPlan.of(60_000, thirty, historyPaused = true, counts = true)
        assertFalse(plan.writeLocalEvent)
        assertTrue(plan.registerWithYouTube)
    }

    // ---- the wiring no JVM test can drive ----

    private fun source(path: String): String =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile }?.readText() ?: error("cannot find $path")

    @Test fun `onPlaybackStatsReady gates both the local row and registerPlayback on the plan`() {
        val text = source("src/main/kotlin/com/metrolist/music/playback/MusicService.kt")
        val start = text.indexOf("override fun onPlaybackStatsReady")
        val end = text.indexOf("private fun saveQueueToDisk")
        val body = text.substring(start, end)
        assertTrue(body.contains("PlaybackStatsPlan.of("))
        assertTrue(body.contains("TastePolicy.resolve("))
        assertTrue(body.contains("plan.writeLocalEvent"))
        assertTrue(body.contains("plan.registerWithYouTube"))
        assertFalse("the raw threshold must not gate registerPlayback any more", body.contains("totalPlayTimeMs >= historyDurationMs"))
        assertTrue(
            "the verdict comes from the setting and the resolved context",
            Regex("""counts\s*=\s*TastePolicy\.counts\(playContext,\s*dataStore\.get\(CountSharedPlaysKey,\s*false\)\)""").containsMatchIn(body),
        )
        assertTrue(
            "the mix is read from the played item, never from the queue held at stats time",
            Regex("""itemIsParivaarMix\s*=\s*mediaItem\.metadata\?\.fromParivaarMix\s*==\s*true""").containsMatchIn(body),
        )
        assertFalse(body.contains("currentQueue"))
        assertTrue(Regex("""inRoom\s*=\s*listenTogetherManager\.isInRoom""").containsMatchIn(body))
        assertTrue(Regex("""suggestedBy\s*=\s*mediaItem\.metadata\?\.suggestedBy""").containsMatchIn(body))
    }
}

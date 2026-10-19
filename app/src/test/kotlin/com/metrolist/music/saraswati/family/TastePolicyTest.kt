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

    @Test fun `Sangat wins over Parivaar Mix when both apply`() {
        assertEquals(PlayContext.Sangat(addedBySelf = false), resolve(inRoom = true, isHost = false, mix = true))
        assertEquals(PlayContext.Sangat(addedBySelf = true), resolve(inRoom = true, isHost = true, mix = true))
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
    }
}

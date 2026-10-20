/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 14 — which song-menu actions a song on this phone keeps.
package com.metrolist.music.saraswati.localmedia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSongMenuPolicyTest {
    @Test
    fun `local songs hide the YouTube-only actions`() {
        listOf(
            SongAction.START_RADIO,
            SongAction.SHARE,
            SongAction.DOWNLOAD,
            SongAction.REMOVE_FROM_CACHE,
            SongAction.ADD_TO_LIBRARY,
            SongAction.SUGGEST_TO_HOST,
            SongAction.SNOOZE,
        ).forEach { assertFalse(it.name, LocalSongMenuPolicy.visible(it, isLocal = true)) }
    }

    @Test
    fun `local songs keep the local actions`() {
        listOf(
            SongAction.PLAY_NEXT,
            SongAction.ADD_TO_QUEUE,
            SongAction.ADD_TO_PLAYLIST,
            SongAction.EDIT,
            SongAction.REMOVE_FROM_HISTORY,
            SongAction.LIKE,
        ).forEach { assertTrue(it.name, LocalSongMenuPolicy.visible(it, isLocal = true)) }
    }

    @Test
    fun `streamed songs are unchanged`() {
        SongAction.values().forEach { assertTrue(it.name, LocalSongMenuPolicy.visible(it, isLocal = false)) }
    }
}

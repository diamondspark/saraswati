/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 14 — which song-menu actions a song on this phone gets. The YouTube-only ones (radio,
// share, download, cache, library, suggest to host) are hidden; the local ones stay. Streamed songs are unchanged.
package com.metrolist.music.saraswati.localmedia

enum class SongAction {
    PLAY_NEXT,
    ADD_TO_QUEUE,
    ADD_TO_PLAYLIST,
    EDIT,
    REMOVE_FROM_HISTORY,
    LIKE,
    START_RADIO,
    SHARE,
    DOWNLOAD,
    REMOVE_FROM_CACHE,
    ADD_TO_LIBRARY,
    SUGGEST_TO_HOST,
    SNOOZE,
    // SARASWATI: Phase 5 fix (P5-I3) — adding a song to a family playlist sends its id to YouTube.
    FAMILY_PLAYLIST,
}

object LocalSongMenuPolicy {
    private val localHidden =
        setOf(
            SongAction.START_RADIO,
            SongAction.SHARE,
            SongAction.DOWNLOAD,
            SongAction.REMOVE_FROM_CACHE,
            SongAction.ADD_TO_LIBRARY,
            SongAction.SUGGEST_TO_HOST,
            SongAction.SNOOZE,
            SongAction.FAMILY_PLAYLIST,
        )

    fun visible(
        action: SongAction,
        isLocal: Boolean,
    ): Boolean = !isLocal || action !in localHidden
}

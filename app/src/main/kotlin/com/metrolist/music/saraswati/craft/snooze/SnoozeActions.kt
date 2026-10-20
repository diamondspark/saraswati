/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 3 — what the snooze menu items do. Snoozing the song that is playing skips to the next
// one; undo and wake only remove the snooze and never skip. Kept apart from the ViewModel so it runs on the JVM.
package com.metrolist.music.saraswati.craft.snooze

import javax.inject.Inject

class SnoozeActions
    @Inject
    constructor(
        private val store: SnoozeStore,
    ) {
        suspend fun snooze(
            videoId: String,
            title: String,
            artist: String,
            playingMediaId: String?,
            skipToNext: () -> Unit,
        ) {
            store.snooze(videoId, title, artist)
            SnoozeUndo.announce(videoId)
            if (playingMediaId == videoId) skipToNext()
        }

        suspend fun undo(videoId: String) = store.wake(videoId)

        suspend fun wake(videoId: String) = store.wake(videoId)
    }

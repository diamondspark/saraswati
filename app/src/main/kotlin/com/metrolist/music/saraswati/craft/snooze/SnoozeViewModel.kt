/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 3 — the snoozed-songs list and the snooze menu actions.
package com.metrolist.music.saraswati.craft.snooze

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.models.MediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SnoozeViewModel
    @Inject
    constructor(
        store: SnoozeStore,
        private val actions: SnoozeActions,
    ) : ViewModel() {
        /** Snoozes still in force, soonest wake-up first. */
        val snoozed: StateFlow<List<SnoozedSong>> =
            store.snoozed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun snooze(
            metadata: MediaMetadata,
            playingMediaId: String?,
            skipToNext: () -> Unit,
        ) {
            viewModelScope.launch {
                actions.snooze(
                    videoId = metadata.id,
                    title = metadata.title,
                    artist = metadata.artists.joinToString { it.name },
                    playingMediaId = playingMediaId,
                    skipToNext = skipToNext,
                )
            }
        }

        fun undo(videoId: String) {
            viewModelScope.launch { actions.undo(videoId) }
        }

        fun wake(videoId: String) {
            viewModelScope.launch { actions.wake(videoId) }
        }
    }

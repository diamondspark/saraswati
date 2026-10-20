/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 3 — the "Snoozed for 30 days · Undo" snackbar. Menus are dismissed as soon as they
// snooze, so the snackbar lives on the app's own host and is announced through this bus.
package com.metrolist.music.saraswati.craft.snooze

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object SnoozeUndo {
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val announced: SharedFlow<String> = events.asSharedFlow()

    fun announce(videoId: String) {
        events.tryEmit(videoId)
    }

    /** Shows the undo snackbar for each announced snooze until one is undone or dismissed. */
    @Composable
    fun Host(
        snackbarHostState: SnackbarHostState,
        onUndo: (String) -> Unit,
    ) {
        val message = stringResource(R.string.snoozed_for_30_days)
        val undo = stringResource(R.string.undo)
        LaunchedEffect(snackbarHostState) {
            announced.collect { videoId ->
                val result =
                    snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = undo,
                        duration = SnackbarDuration.Short,
                    )
                if (result == SnackbarResult.ActionPerformed) onUndo(videoId)
            }
        }
    }
}

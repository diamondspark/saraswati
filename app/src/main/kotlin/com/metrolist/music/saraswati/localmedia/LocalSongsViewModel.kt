/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 14 — the On this phone screen's state. Nothing is requested until the person taps Allow;
// the Local files switch turned off shows the off state and reads nothing; a scan happens only when access is
// granted and the switch is not off (LocalSongsRepository enforces that again).
package com.metrolist.music.saraswati.localmedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

sealed interface LocalSongsState {
    data object Unsupported : LocalSongsState

    data object Off : LocalSongsState

    data class NeedsPermission(
        val deniedCount: Int,
    ) : LocalSongsState

    data object Scanning : LocalSongsState

    data object Empty : LocalSongsState

    data class Songs(
        val list: List<Song>,
    ) : LocalSongsState
}

@HiltViewModel
class LocalSongsViewModel
    @Inject
    constructor(
        private val repository: LocalSongsRepository,
    ) : ViewModel() {
        private val _access = MutableStateFlow(repository.currentAccess())
        val access: StateFlow<LocalAccess> = _access.asStateFlow()

        private val denied = MutableStateFlow(0)
        private val scanning = MutableStateFlow(false)

        // With the switch off the database is not even read; the list stays hidden and nothing is shown.
        private val songs: Flow<List<Song>> =
            repository.storedSwitch.flatMapLatest { stored ->
                if (stored == false) flowOf(emptyList()) else repository.storedSongs
            }

        val state: StateFlow<LocalSongsState> =
            combine(_access, repository.storedSwitch, songs, scanning, denied) { access, stored, list, isScanning, deniedCount ->
                when {
                    access == LocalAccess.UNSUPPORTED -> LocalSongsState.Unsupported
                    stored == false -> LocalSongsState.Off
                    access == LocalAccess.NOT_GRANTED -> LocalSongsState.NeedsPermission(deniedCount)
                    list.isEmpty() && isScanning -> LocalSongsState.Scanning
                    list.isEmpty() -> LocalSongsState.Empty
                    else -> LocalSongsState.Songs(list)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalSongsState.Scanning)

        /** Called when the screen comes back to the front: re-reads access and runs the launch-time rescan if one is due. */
        fun onResume() {
            _access.value = repository.currentAccess()
            if (_access.value != LocalAccess.GRANTED) return
            viewModelScope.launch(Dispatchers.IO) { repository.syncIfDue(Instant.now()) }
        }

        /** The system dialog's answer. A grant turns the switch on and scans once; a refusal is counted. */
        fun onPermissionResult(granted: Boolean) {
            _access.value = repository.currentAccess()
            if (!granted) {
                denied.update { it + 1 }
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                repository.setEnabled(true)
                scan()
            }
        }

        /** The Turn on button of the off state, once access is already granted. */
        fun turnOn() {
            viewModelScope.launch(Dispatchers.IO) {
                repository.setEnabled(true)
                scan()
            }
        }

        /** Check again, and the refresh action: scans only while access is granted and the switch is not off. */
        fun refresh() {
            _access.value = repository.currentAccess()
            if (_access.value != LocalAccess.GRANTED) return
            viewModelScope.launch(Dispatchers.IO) {
                if (repository.storedSwitch.first() == false) return@launch
                scan()
            }
        }

        fun play(
            index: Int,
            start: (Queue) -> Unit,
        ) {
            val list = (state.value as? LocalSongsState.Songs)?.list ?: return
            start(ListQueue(items = list.map { it.toMediaItem() }, startIndex = index.coerceIn(0, list.lastIndex)))
        }

        fun shuffle(start: (Queue) -> Unit) {
            val list = (state.value as? LocalSongsState.Songs)?.list ?: return
            start(ListQueue(items = list.shuffled().map { it.toMediaItem() }))
        }

        private suspend fun scan() {
            scanning.value = true
            try {
                repository.syncNow()
            } finally {
                scanning.value = false
            }
        }
    }

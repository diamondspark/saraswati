/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — the Sangat section's state: the live rooms, the car prompt, the one-time name ask,
// and what a passenger's phone shows while it is a remote controller. It reads through small ports, so the rules
// (hidden while Sangat is off, a dismissed prompt never returns, a blank name is asked for once) are unit tested
// without the room machinery. The real ports are in SangatSources.kt.
package com.metrolist.music.saraswati.family.sangat

import androidx.lifecycle.ViewModel
import com.metrolist.music.saraswati.family.LiveSangatRoom
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the room machinery offers the Sangat screens. The real one is [ListenTogetherSangatPort]. */
interface SangatRoomPort {
    /** The code of the room this phone is in, or null. */
    val currentRoomCode: Flow<String?>
    val remoteController: Flow<Boolean>
    val remoteNowPlaying: Flow<RemoteNowPlaying?>

    /** The room this phone is in as the screens show it, or null. */
    val roomInfo: Flow<SangatRoomInfo?>

    /** Suggests a song to the room; a car room approves it itself. */
    fun suggestSong(candidate: SangatCandidate)

    fun createRoom(username: String)

    fun joinRoom(
        roomCode: String,
        username: String,
        remoteController: Boolean,
    )

    fun leaveRoom()
}

/** The live rooms the hub reports, and whether Sangat is switched on. */
interface SangatFeed {
    val liveRooms: Flow<List<LiveSangatRoom>>
    val sangatOn: Flow<Boolean>
}

/** The name this phone goes by in a room. */
interface SangatNames {
    suspend fun saved(): String

    suspend fun save(name: String)
}

/**
 * Car rooms whose prompt the person has dismissed. It lives as long as the process, so a dismissed prompt does not
 * come back while the app stays open; a restart may show it once more for a room that is still going.
 */
@Singleton
class SangatDismissals
    @Inject
    constructor() {
        private val codes = MutableStateFlow<Set<String>>(emptySet())
        val dismissed: StateFlow<Set<String>> = codes.asStateFlow()

        fun dismiss(roomCode: String) {
            codes.update { it + roomCode }
        }

        fun restore(roomCode: String) {
            codes.update { it - roomCode }
        }
    }

@HiltViewModel
class SangatViewModel
    internal constructor(
        private val feed: SangatFeed,
        private val rooms: SangatRoomPort,
        private val names: SangatNames,
        private val dismissals: SangatDismissals,
        private val scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(
            feed: SangatFeed,
            rooms: SangatRoomPort,
            names: SangatNames,
            dismissals: SangatDismissals,
        ) : this(feed, rooms, names, dismissals, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        val sangatOn: StateFlow<Boolean> = feed.sangatOn.stateIn(scope, SharingStarted.Eagerly, false)

        /** Live rooms, or nothing at all while Sangat is off. */
        val live: StateFlow<List<LiveSangatRoom>> =
            combine(feed.liveRooms, sangatOn) { list, on -> if (on) list else emptyList() }
                .stateIn(scope, SharingStarted.Eagerly, emptyList())

        val currentRoomCode: StateFlow<String?> = rooms.currentRoomCode.stateIn(scope, SharingStarted.Eagerly, null)

        val remoteController: StateFlow<Boolean> = rooms.remoteController.stateIn(scope, SharingStarted.Eagerly, false)

        val remoteNowPlaying: StateFlow<RemoteNowPlaying?> =
            rooms.remoteNowPlaying.stateIn(scope, SharingStarted.Eagerly, null)

        val roomInfo: StateFlow<SangatRoomInfo?> = rooms.roomInfo.stateIn(scope, SharingStarted.Eagerly, null)

        /** The live list for the Sangat section: this phone's own hosted room left out, a joined room marked. */
        val rows: StateFlow<List<SangatRow>> =
            combine(live, roomInfo, remoteController) { list, info, remote -> SangatDisplay.rows(list, info, remote) }
                .stateIn(scope, SharingStarted.Eagerly, emptyList())

        /** The Home banner, or null when nothing is live (or while this phone hosts its own room). */
        val banner: StateFlow<SangatBanner?> =
            combine(live, roomInfo, remoteController) { list, info, remote -> SangatDisplay.banner(list, info, remote) }
                .stateIn(scope, SharingStarted.Eagerly, null)

        /** The car room to offer: live, in the car, not this phone's own room, and not dismissed. */
        val prompt: StateFlow<LiveSangatRoom?> =
            combine(live, currentRoomCode, dismissals.dismissed) { list, here, gone ->
                list.firstOrNull { it.carMode && it.roomCode != here && it.roomCode !in gone }
            }.stateIn(scope, SharingStarted.Eagerly, null)

        private val _askName = MutableStateFlow(false)

        /** True while the name dialog should be shown: a start or join is waiting for a name. */
        val askName: StateFlow<Boolean> = _askName.asStateFlow()

        private val _enteredCarRoom = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Fires after a join into a car room, so the screen can open the remote controller. */
        val enteredCarRoom: SharedFlow<Unit> = _enteredCarRoom.asSharedFlow()

        private var pending: ((String) -> Unit)? = null

        /** Starts a normal Sangat on this phone. */
        fun start() {
            withName { name -> rooms.createRoom(name) }
        }

        /** Joins a live room. A car room is joined as a remote controller, so nothing plays on this phone. */
        fun join(room: LiveSangatRoom) {
            withName { name ->
                rooms.joinRoom(room.roomCode, name, remoteController = room.carMode)
                if (room.carMode) _enteredCarRoom.tryEmit(Unit)
            }
        }

        private val _dismissedNote = MutableStateFlow<String?>(null)

        /** The room whose prompt was just dismissed: the Home screen says where to join later and offers Show again. */
        val dismissedNote: StateFlow<String?> = _dismissedNote.asStateFlow()

        fun dismissPrompt(roomCode: String) {
            dismissals.dismiss(roomCode)
            _dismissedNote.value = roomCode
        }

        /** The "Show again" action: the prompt for that room comes back. */
        fun showPromptAgain(roomCode: String) {
            dismissals.restore(roomCode)
            _dismissedNote.value = null
        }

        fun clearDismissedNote() {
            _dismissedNote.value = null
        }

        /** The name this phone goes by in a room, empty before one was saved. */
        suspend fun savedName(): String = names.saved().trim()

        /** Saves the name typed into the dialog and carries on with the start or join that asked for it. */
        fun submitName(name: String) {
            val clean = name.trim().take(MAX_NAME_LENGTH)
            val action = pending
            if (clean.isEmpty() || action == null) return
            pending = null
            _askName.value = false
            scope.launch {
                names.save(clean)
                action(clean)
            }
        }

        fun cancelNameRequest() {
            pending = null
            _askName.value = false
        }

        fun leave() {
            rooms.leaveRoom()
        }

        private fun withName(action: (String) -> Unit) {
            scope.launch {
                val name = names.saved().trim()
                if (name.isEmpty()) {
                    pending = action
                    _askName.value = true
                } else {
                    action(name)
                }
            }
        }

        companion object {
            /** The same limit as a Listen Together username. */
            const val MAX_NAME_LENGTH = 50
        }
    }

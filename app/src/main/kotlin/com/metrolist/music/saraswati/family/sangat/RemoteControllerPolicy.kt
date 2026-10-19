/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — remote-controller mode for car passengers. The car's phone hosts the Sangat and
// plays; a passenger joins as a remote controller: the phone shows the room and suggests songs but never
// plays them, so the family does not hear two copies of the same song a few hundred milliseconds apart.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.listentogether.ListenTogetherEvent
import com.metrolist.music.listentogether.RoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn

object RemoteControllerPolicy {
    /** The host plays for the room; a guest plays too unless it joined as a remote controller. */
    fun drivesLocalPlayer(
        isHost: Boolean,
        remoteController: Boolean,
    ): Boolean = isHost || !remoteController
}

/**
 * Whether this phone joined the current room as a remote controller, and the room as that passenger sees it.
 * [persist] stores the flag so a process death in the middle of a drive does not rejoin the room as a normal
 * guest (and start playing).
 */
class RemoteControllerMode(
    scope: CoroutineScope,
    roomState: StateFlow<RoomState?>,
    private val persist: (Boolean) -> Unit = {},
) {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    // False until this process makes a choice of its own, so a late read of the stored flag cannot undo it.
    @Volatile
    private var decided = false

    val nowPlaying: StateFlow<RemoteNowPlaying?> =
        combine(_enabled, roomState) { remote, room -> if (remote) RemoteNowPlaying.from(room) else null }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun enter() {
        decided = true
        if (!_enabled.getAndUpdate { true }) persist(true)
    }

    fun clear() {
        decided = true
        if (_enabled.getAndUpdate { false }) persist(false)
    }

    /** The flag stored by an earlier process; ignored once this process has joined or left on its own. */
    fun restore() {
        if (!decided) _enabled.value = true
    }

    fun drives(isHost: Boolean): Boolean = RemoteControllerPolicy.drivesLocalPlayer(isHost, _enabled.value)
}

/**
 * The first stop for every room event on the way to the player. [route] returns true when the event was
 * consumed here (a remote controller's room events must not reach the guest handlers that call
 * play/pause/seek/setMediaItems), and false when the manager's own handlers carry on as before.
 */
class RemoteEventRouter(
    private val mode: RemoteControllerMode,
    private val isHost: () -> Boolean,
    private val pauseLocalPlayback: () -> Unit,
) {
    fun route(event: ListenTogetherEvent): Boolean {
        val asHost =
            when (event) {
                is ListenTogetherEvent.JoinApproved,
                is ListenTogetherEvent.PlaybackSync,
                is ListenTogetherEvent.SyncStateReceived,
                -> isHost()
                is ListenTogetherEvent.Reconnected -> event.isHost
                else -> return false
            }
        if (mode.drives(asHost)) return false
        if (event is ListenTogetherEvent.JoinApproved) pauseLocalPlayback()
        return true
    }
}

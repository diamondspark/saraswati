/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — remote-controller mode for car passengers. The car's phone hosts the Sangat and
// plays; a passenger joins as a remote controller: the phone shows the room and suggests songs but never
// plays them, so the family does not hear two copies of the same song a few hundred milliseconds apart.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.listentogether.ConnectionState
import com.metrolist.music.listentogether.ListenTogetherEvent
import com.metrolist.music.listentogether.RoomState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

object RemoteControllerPolicy {
    /** The host plays for the room; a guest plays too unless it joined as a remote controller. */
    fun drivesLocalPlayer(
        isHost: Boolean,
        remoteController: Boolean,
    ): Boolean = isHost || !remoteController
}

/**
 * Whether a guest's own player is locked while it sits in a room. A halted connection (the hub answered 503, the
 * token store was closed) keeps the room for a later retry but syncs nothing, so it must not keep the guest from
 * playing its own music in the meantime.
 */
object GuestPlaybackLock {
    fun blocks(
        isInRoom: Boolean,
        isHost: Boolean,
        connection: ConnectionState,
    ): Boolean = isInRoom && !isHost && connection != ConnectionState.ERROR
}

/**
 * A remote controller never hosts: it plays nothing, so as host it would publish an idle local player to everyone
 * (Phase 4 Task 10 carry-over). When the room makes it the host anyway (the car's phone left, or a reconnect says
 * so), it leaves, and the room hands the role to someone who can play.
 */
object RemoteHostGuard {
    fun mustLeave(
        event: ListenTogetherEvent,
        myUserId: String?,
        remoteController: Boolean,
    ): Boolean =
        remoteController &&
            when (event) {
                is ListenTogetherEvent.HostChanged -> myUserId != null && event.newHostId == myUserId
                is ListenTogetherEvent.Reconnected -> event.isHost
                else -> false
            }
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

    // Done once the stored flag has been read (or this process chose for itself, or the read failed): a room event
    // routed before then could find the flag still false and let a rejoined passenger's phone play.
    private val restored = CompletableDeferred<Unit>()

    val nowPlaying: StateFlow<RemoteNowPlaying?> =
        combine(_enabled, roomState) { remote, room -> if (remote) RemoteNowPlaying.from(room) else null }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun enter() {
        decided = true
        restored.complete(Unit)
        if (!_enabled.getAndUpdate { true }) persist(true)
    }

    fun clear() {
        decided = true
        restored.complete(Unit)
        if (_enabled.getAndUpdate { false }) persist(false)
    }

    /** The flag stored by an earlier process; ignored once this process has joined or left on its own. */
    fun restore() {
        if (!decided) _enabled.value = true
        restored.complete(Unit)
    }

    /** The stored flag was read and held nothing, or could not be read: nothing to wait for any more. */
    fun restoredNothing() {
        restored.complete(Unit)
    }

    /**
     * Returns once the stored flag has been applied, so the caller routes room events knowing the mode. Gives up
     * after [RESTORE_WAIT_MS] rather than hold every room event behind a stuck read.
     */
    suspend fun awaitRestored() {
        withTimeoutOrNull(RESTORE_WAIT_MS) { restored.await() }
    }

    fun drives(isHost: Boolean): Boolean = RemoteControllerPolicy.drivesLocalPlayer(isHost, _enabled.value)

    private companion object {
        const val RESTORE_WAIT_MS = 3_000L
    }
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

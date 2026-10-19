/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 15 — the car's view of Listen Together: the manager's role and room as a
// [CarSangatState], and "make a room" that answers only once the room exists and this phone is its host.
//
// Listen Together is normally wired up by the app screen. A car can start a Sangat with the screen never opened,
// so [attachPlayer] (set by the music service) hands the manager the player before the room is made.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.listentogether.ListenTogetherManager
import com.metrolist.music.listentogether.RoomRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ListenTogetherCarRoom(
    private val manager: ListenTogetherManager,
    scope: CoroutineScope,
    private val mainContext: kotlin.coroutines.CoroutineContext,
    private val timeoutMs: Long = CREATE_TIMEOUT_MS,
) : CarSangatRoom {
    @Volatile var attachPlayer: () -> Unit = {}

    override val state: StateFlow<CarSangatState> =
        combine(manager.role, manager.roomState) { role, room ->
            if (role == RoomRole.HOST && room != null) {
                CarSangatState(hosting = true, joinedCount = (room.usersCount - 1).coerceAtLeast(0))
            } else {
                CarSangatState.IDLE
            }
        }.stateIn(scope, SharingStarted.Eagerly, CarSangatState.IDLE)

    override suspend fun createRoom(username: String): String? {
        withContext(mainContext) {
            attachPlayer()
            if (manager.isInRoom) manager.leaveRoom()
            manager.createRoom(username)
        }
        val code =
            withTimeoutOrNull(timeoutMs) {
                combine(manager.role, manager.roomState) { role, room -> room?.takeIf { role == RoomRole.HOST }?.roomCode }
                    .first { it != null }
            }
        if (code == null) withContext(mainContext) { manager.leaveRoom() }
        return code
    }

    override fun leave() = manager.leaveRoom()

    companion object {
        const val CREATE_TIMEOUT_MS = 15_000L
    }
}

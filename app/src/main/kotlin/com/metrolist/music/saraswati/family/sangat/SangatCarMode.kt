/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — car mode for a room this phone hosts. markHostedRoom tells the hub the room is
// being driven (so passengers' phones prompt), and [active] then stays true until [end] (the client calls it
// when this phone is no longer the host of a room). It is only ever set from a success: a hub that said no
// leaves car mode off, so joins are not auto-approved on the strength of a call that did not land.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.HubIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

class SangatCarMode(
    private val hub: SangatHubApi,
    private val identity: HubIdentity,
) {
    private val state = MutableStateFlow(false)
    val active: StateFlow<Boolean> = state.asStateFlow()

    suspend fun markHostedRoom(roomCode: String): Result<Unit> {
        val token =
            identity.sessionToken()
                ?: return Result.failure(IllegalStateException("this phone has no hub session"))
        val result =
            try {
                hub.setSangatCarMode(token, roomCode, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        if (result.isSuccess) state.value = true
        return result
    }

    fun end() {
        state.value = false
    }
}

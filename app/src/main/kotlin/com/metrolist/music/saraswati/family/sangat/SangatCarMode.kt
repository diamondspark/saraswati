/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — car mode for a room this phone hosts. markHostedRoom tells the hub the room is
// being driven (so passengers' phones prompt), and [active] then stays true until [end] (the client calls it
// when this phone is no longer the host of a room). It is only ever set from a success: a hub that said no
// leaves car mode off, so joins are not auto-approved on the strength of a call that did not land.
//
// Final fix wave (I3): the car room's code is kept on the phone too, so car mode survives the app process dying
// mid-drive. It is forgotten only when this phone stops being the host ([endsWith]) or its session is gone
// ([forget]), never just because the process started. On reconnecting as host to that same room, [resumeOnReconnect]
// turns car mode back on and the caller approves what is waiting.
package com.metrolist.music.saraswati.family.sangat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.SangatCarRoomCodeKey
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.saraswati.family.HubIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

/** Where the car room's code is kept between app processes. */
interface CarRoomStore {
    suspend fun load(): String?

    suspend fun save(roomCode: String)

    suspend fun clear()

    object None : CarRoomStore {
        override suspend fun load(): String? = null

        override suspend fun save(roomCode: String) = Unit

        override suspend fun clear() = Unit
    }
}

class DataStoreCarRoomStore(
    private val dataStore: DataStore<Preferences>,
) : CarRoomStore {
    override suspend fun load(): String? = dataStore.data.first()[SangatCarRoomCodeKey]?.takeIf { it.isNotBlank() }

    override suspend fun save(roomCode: String) {
        dataStore.edit { it[SangatCarRoomCodeKey] = roomCode }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(SangatCarRoomCodeKey) }
    }
}

class SangatCarMode(
    private val hub: SangatHubApi,
    private val identity: HubIdentity,
    private val store: CarRoomStore = CarRoomStore.None,
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
        if (result.isSuccess) {
            state.value = true
            guarded { store.save(roomCode) }
        }
        return result
    }

    /** This phone is no longer the host: car mode is over, and so is the memory of the room. */
    suspend fun end() {
        state.value = false
        guarded { store.clear() }
    }

    /** The saved host session is gone (expired or refused): forget the room without touching the live flag. */
    suspend fun forget() {
        guarded { store.clear() }
    }

    /**
     * This phone reconnected to [roomCode]. When it did so as the host of the room it was driving, car mode comes
     * back on; the result says so, and the caller then approves the joins and songs that waited.
     */
    suspend fun resumeOnReconnect(
        roomCode: String,
        isHost: Boolean,
    ): Boolean {
        if (!isHost) return false
        val saved = guarded { store.load() } ?: return false
        if (!saved.equals(roomCode, ignoreCase = true)) return false
        state.value = true
        return true
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        /** Car mode ends when the phone goes from host to anything else; the first role of a new process is not that. */
        fun endsWith(
            previous: RoomRole,
            current: RoomRole,
        ): Boolean = previous == RoomRole.HOST && current != RoomRole.HOST
    }
}

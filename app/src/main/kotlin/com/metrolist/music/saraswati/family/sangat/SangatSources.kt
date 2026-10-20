/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — the production sources behind SangatViewModel's ports: the Listen Together manager
// for the room, the feed gate and watcher for the live rooms, and the Listen Together username in DataStore.
package com.metrolist.music.saraswati.family.sangat

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.ListenTogetherUsernameKey
import com.metrolist.music.listentogether.ListenTogetherManager
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.LiveSangatRoom
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ListenTogetherSangatPort(
    private val manager: ListenTogetherManager,
) : SangatRoomPort {
    override val currentRoomCode: Flow<String?>
        get() = manager.roomState.map { state -> state?.roomCode?.takeIf { it.isNotEmpty() } }

    override val remoteController: Flow<Boolean>
        get() = manager.isRemoteController

    override val remoteNowPlaying: Flow<RemoteNowPlaying?>
        get() = manager.remoteNowPlaying

    override fun createRoom(username: String) = manager.createRoom(username)

    override fun joinRoom(
        roomCode: String,
        username: String,
        remoteController: Boolean,
    ) = manager.joinRoom(roomCode, username, remoteController)

    override fun leaveRoom() = manager.leaveRoom()
}

class WatcherSangatFeed(
    private val watcher: SangatLiveWatcher,
    private val gate: FamilyFeatureGate,
) : SangatFeed {
    override val liveRooms: Flow<List<LiveSangatRoom>>
        get() = watcher.liveRooms

    override val sangatOn: Flow<Boolean>
        get() = gate.enabled.map { it[FamilyFeature.SANGAT] ?: true }
}

class DataStoreSangatNames(
    private val context: Context,
) : SangatNames {
    override suspend fun saved(): String =
        withContext(Dispatchers.IO) { context.dataStore.get(ListenTogetherUsernameKey, "") }

    override suspend fun save(name: String) {
        withContext(Dispatchers.IO) {
            context.dataStore.edit { it[ListenTogetherUsernameKey] = name }
        }
    }
}

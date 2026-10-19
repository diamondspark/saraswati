/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — the remote-controller flag, kept across a process death. The Listen Together
// client rejoins a room it still has a session for, and without this a passenger's phone that was killed in
// the background would come back as an ordinary guest and start playing the car's song.
package com.metrolist.music.saraswati.family.sangat

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
object RemoteControllerStore {
    val Key = booleanPreferencesKey("saraswatiSangatRemoteController")

    // One writer at a time, so a quick join-leave cannot land its writes out of order.
    private val writer = Dispatchers.IO.limitedParallelism(1)

    fun save(
        context: Context,
        scope: CoroutineScope,
        remote: Boolean,
    ) {
        scope.launch(writer) {
            try {
                context.safeDataStoreEdit { prefs ->
                    if (remote) prefs[Key] = true else prefs.remove(Key)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to store the remote-controller flag")
            }
        }
    }

    fun restore(
        context: Context,
        scope: CoroutineScope,
        mode: RemoteControllerMode,
    ) {
        scope.launch(writer) {
            try {
                if (context.dataStore.data.first()[Key] == true) mode.restore()
            } catch (e: Exception) {
                Timber.e(e, "Failed to read the remote-controller flag")
            }
        }
    }
}

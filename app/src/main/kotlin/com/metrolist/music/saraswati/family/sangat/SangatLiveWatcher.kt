/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — how a passenger's phone learns a family member is driving with Sangat on.
// There is no push channel (Spec note 2), so while the app is in the foreground this asks the hub for the
// live rooms every [POLL_INTERVAL_MS], between [start] and [stop] and no other time. Nothing is asked while
// the Sangat kill-switch is off or this phone has no session. A hub that cannot be reached, or answers
// something unusable, gives an empty list; it never throws. Rooms this phone hosts are left out, and each room
// code in car mode is announced once on [carPrompts] (forgotten when the room leaves the list).
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.LiveSangatRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class SangatLiveWatcher(
    private val hub: SangatHubApi,
    private val identity: HubIdentity,
    private val gate: FamilyFeatureGate,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val rooms = MutableStateFlow<List<LiveSangatRoom>>(emptyList())
    val liveRooms: StateFlow<List<LiveSangatRoom>> = rooms.asStateFlow()

    private val prompts = MutableSharedFlow<LiveSangatRoom>(extraBufferCapacity = PROMPT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val carPrompts: SharedFlow<LiveSangatRoom> = prompts.asSharedFlow()

    private val _lastPolledAt = MutableStateFlow<Long?>(null)

    /** When the last poll finished ([clock] milliseconds), or null before the first one. */
    val lastPolledAt: StateFlow<Long?> = _lastPolledAt.asStateFlow()

    private val prompted = HashSet<String>()
    private var job: Job? = null

    /** Polls at once, then every [POLL_INTERVAL_MS], until [stop]. Calling it again restarts the loop. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        job?.cancel()
        job =
            scope.launch {
                while (isActive) {
                    pollOnce()
                    delay(POLL_INTERVAL_MS)
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        rooms.value = emptyList()
    }

    private suspend fun pollOnce() {
        val fetched =
            if (!gate.isEnabled(FamilyFeature.SANGAT)) {
                emptyList()
            } else {
                val token = identity.sessionToken()
                if (token == null) {
                    emptyList()
                } else {
                    try {
                        hub.getLiveSangat(token).getOrNull() ?: emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
        val self = identity.deviceId()
        val visible = fetched.filter { self == null || it.hostDeviceId != self }
        currentCoroutineContext().ensureActive()
        rooms.value = visible
        _lastPolledAt.value = clock()
        announce(visible)
    }

    private fun announce(visible: List<LiveSangatRoom>) {
        val fresh =
            synchronized(prompted) {
                prompted.retainAll(visible.mapTo(HashSet()) { it.roomCode })
                visible.filter { it.carMode && prompted.add(it.roomCode) }
            }
        fresh.forEach { prompts.tryEmit(it) }
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L
        private const val PROMPT_BUFFER = 8
    }
}

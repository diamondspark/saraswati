/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — how a passenger's phone learns a family member is driving with Sangat on.
// There is no push channel (Spec note 2), so while the app is in the foreground this asks the hub for the
// live rooms every [POLL_INTERVAL_MS], between [start] and [stop] and no other time. Nothing is asked while
// the Sangat kill-switch is off or this phone has no session. A hub that cannot be reached, or answers
// something unusable, gives an empty list; it never throws. Rooms this phone hosts are left out, and each room
// code is announced once on [carPrompts] (forgotten when the room leaves the list).
//
// The kill-switches would otherwise only be read when the app resumes, so a phone left on screen would keep
// a stale answer for as long as it stays there. The watcher therefore asks the gate to refresh itself when the
// hub answers 503 (Sangat is off) and, whatever the poll says, every [GATE_REFRESH_EVERY_POLLS] polls — which
// also lets a gate that says "off" find out it has been switched back on, though nothing is polled meanwhile.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubException
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

    private val answered = MutableStateFlow<List<LiveSangatRoom>?>(null)

    /**
     * The visible list from the last time the hub actually answered (or Sangat was switched off) while the watcher ran.
     * Null before that and after [stop]; a failed call or a missing session leaves it as it was. [liveRooms] goes empty
     * on those, so only this one says whether a room has really ended.
     */
    val answeredLive: StateFlow<List<LiveSangatRoom>?> = answered.asStateFlow()

    private val prompts = MutableSharedFlow<LiveSangatRoom>(extraBufferCapacity = PROMPT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val carPrompts: SharedFlow<LiveSangatRoom> = prompts.asSharedFlow()

    private val _lastPolledAt = MutableStateFlow<Long?>(null)

    /** When the last poll finished ([clock] milliseconds), or null before the first one. */
    val lastPolledAt: StateFlow<Long?> = _lastPolledAt.asStateFlow()

    /** Whether the live list was polled within [LIST_FRESH_MS]: the list a join may trust without asking the hub again. */
    fun listIsFresh(): Boolean = _lastPolledAt.value?.let { clock() - it <= LIST_FRESH_MS } == true

    private val prompted = HashSet<String>()
    private var job: Job? = null

    /** True between [start] and [stop]. */
    val running: Boolean get() = job?.isActive == true

    /** Polls at once, then every [POLL_INTERVAL_MS], until [stop]. Calling it again restarts the loop. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        job?.cancel()
        job =
            scope.launch {
                var polls = 0
                while (isActive) {
                    pollOnce(this)
                    if (++polls % GATE_REFRESH_EVERY_POLLS == 0) refreshGateAsync(this)
                    delay(POLL_INTERVAL_MS)
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        rooms.value = emptyList()
        answered.value = null
    }

    private suspend fun pollOnce(scope: CoroutineScope) {
        var answeredByHub = false
        val fetched =
            if (!gate.isEnabled(FamilyFeature.SANGAT)) {
                answeredByHub = true
                emptyList()
            } else {
                val token = identity.sessionToken()
                if (token == null) {
                    emptyList()
                } else {
                    try {
                        val answer = hub.getLiveSangat(token)
                        if ((answer.exceptionOrNull() as? HubException)?.status == 503) refreshGateAsync(scope)
                        answeredByHub = answer.isSuccess
                        answer.getOrNull() ?: emptyList()
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
        if (answeredByHub) answered.value = visible
        _lastPolledAt.value = clock()
        announce(visible)
    }

    /** The hub's live list asked for now, or null when it cannot be had: Sangat off, no session, or the hub failed. */
    suspend fun fetchLiveRooms(): List<LiveSangatRoom>? {
        if (!gate.isEnabled(FamilyFeature.SANGAT)) return null
        val token = identity.sessionToken() ?: return null
        return try {
            hub.getLiveSangat(token).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // Beside the poll, not in it: a slow hub or disk must not hold up the list the screen shows.
    private fun refreshGateAsync(scope: CoroutineScope) {
        scope.launch {
            try {
                gate.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The gate keeps its last answer.
            }
        }
    }

    private fun announce(visible: List<LiveSangatRoom>) {
        val fresh =
            synchronized(prompted) {
                prompted.retainAll(visible.mapTo(HashSet()) { it.roomCode })
                visible.filter { prompted.add(it.roomCode) }
            }
        fresh.forEach { prompts.tryEmit(it) }
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L

        /** A poll this recent is current enough to answer a join; an older one is asked of the hub again. */
        const val LIST_FRESH_MS = 10_000L
        private const val PROMPT_BUFFER = 8

        /** Every 10th poll, so about every 5 minutes. */
        const val GATE_REFRESH_EVERY_POLLS = 10
    }
}

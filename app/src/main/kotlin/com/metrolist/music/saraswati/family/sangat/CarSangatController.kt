/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 15 — start and end a Sangat from the car, with one row or one button.
//
// Start makes a room on this phone, waits until this phone really is its host, and only then tells the hub the
// room is being driven (SangatCarMode.markHostedRoom), so passengers' phones are prompted and joins are approved
// by themselves. Marking a room whose host role is not yet in place would leave car mode on for a room that is not
// one (Phase 4 review M10). If the hub will not take the mark the room is left again: a Sangat that nobody is told
// about is worse than none.
//
// A second tap while the first is still working gets the first one's answer, not a second room or an end.
package com.metrolist.music.saraswati.family.sangat

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.HubIdentity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Whether this phone is hosting a Sangat, and how many others are in it. */
data class CarSangatState(
    val hosting: Boolean,
    val joinedCount: Int,
) {
    companion object {
        val IDLE = CarSangatState(hosting = false, joinedCount = 0)
    }
}

enum class CarSangatResult { Started, Ended, PairRequired, Disabled, NoConnection }

/** What the controller needs from the room machinery; the real one is [ListenTogetherCarRoom]. */
interface CarSangatRoom {
    val state: StateFlow<CarSangatState>

    /** Makes a room and returns its code once this phone is its host, or null if that did not happen in time. */
    suspend fun createRoom(username: String): String?

    fun leave()
}

class CarSangatController(
    private val room: CarSangatRoom,
    private val carMode: SangatCarMode,
    private val identity: HubIdentity,
    private val enabled: (FamilyFeature) -> Boolean,
    private val username: suspend () -> String,
) {
    val state: StateFlow<CarSangatState> get() = room.state

    private val busy = Mutex()

    @Volatile private var last = CarSangatResult.NoConnection

    suspend fun toggle(): CarSangatResult {
        if (!busy.tryLock()) return busy.withLock { last }
        try {
            return run().also { last = it }
        } finally {
            busy.unlock()
        }
    }

    private suspend fun run(): CarSangatResult {
        // Ending is always allowed, even if the owner switched Sangat off while it was going.
        if (room.state.value.hosting) {
            room.leave()
            return CarSangatResult.Ended
        }
        if (!enabled(FamilyFeature.SANGAT)) return CarSangatResult.Disabled
        if (identity.sessionToken() == null) return CarSangatResult.PairRequired
        val code = room.createRoom(username()) ?: return CarSangatResult.NoConnection
        val marked =
            try {
                carMode.markHostedRoom(code)
            } catch (e: CancellationException) {
                room.leave()
                throw e
            }
        if (marked.isFailure) {
            room.leave()
            return CarSangatResult.NoConnection
        }
        return CarSangatResult.Started
    }
}

/** What the player holds right now, handed back unchanged when a car row asks for something that is not a new queue. */
fun currentQueue(player: Player): MediaItemsWithStartPosition {
    val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
    if (items.isEmpty()) return MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
    return MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
}

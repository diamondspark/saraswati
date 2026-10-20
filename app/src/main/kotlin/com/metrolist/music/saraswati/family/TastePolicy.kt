/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 13 — whose taste a play shapes. A song someone else picked in a Sangat, and the
// shared Parivaar Mix, are not what this person chose, so by default they leave no listen history and no
// YouTube watch history behind. The Saraswati setting CountSharedPlaysKey turns them on.
package com.metrolist.music.saraswati.family

import com.metrolist.music.saraswati.localmedia.LocalSongIds

sealed interface PlayContext {
    /** Nothing shared: the person chose this song themselves. */
    data object Solo : PlayContext

    /** Playing in a Sangat room; [addedBySelf] is whether this person was the one who picked the track. */
    data class Sangat(
        val addedBySelf: Boolean,
    ) : PlayContext

    /** Playing from the family's Parivaar Mix. */
    data object ParivaarMix : PlayContext
}

object TastePolicy {
    fun counts(
        context: PlayContext,
        countSharedPlays: Boolean,
    ): Boolean =
        when (context) {
            PlayContext.Solo -> true
            is PlayContext.Sangat -> context.addedBySelf || countSharedPlays
            PlayContext.ParivaarMix -> countSharedPlays
        }

    /**
     * Decided from the played item, not from the queue the phone holds when the stats arrive (a queue swap, a
     * restored plain list or a radio started over a mix track would otherwise change the answer).
     *
     * A Parivaar Mix track with nobody's name on it is the mix's, even while hosting a Sangat. Otherwise the
     * host's own tracks carry no `suggestedBy` and a passenger's carry the passenger's name; a guest owns only a
     * track carrying its own name. A name on the item says it was a room pick even after the room is left.
     */
    fun resolve(
        inRoom: Boolean,
        isHost: Boolean,
        myUsername: String,
        suggestedBy: String?,
        itemIsParivaarMix: Boolean,
    ): PlayContext {
        val by = suggestedBy?.trim().orEmpty()
        val me = myUsername.trim()
        val isMine = me.isNotEmpty() && by.equals(me, ignoreCase = true)
        return when {
            itemIsParivaarMix && by.isEmpty() -> PlayContext.ParivaarMix
            inRoom -> PlayContext.Sangat(addedBySelf = if (isHost) by.isEmpty() || isMine else isMine)
            by.isNotEmpty() -> PlayContext.Sangat(addedBySelf = isMine)
            else -> PlayContext.Solo
        }
    }
}

/** What `onPlaybackStatsReady` does for one finished play, decided apart from the service so a test can run it. */
data class PlaybackStatsPlan(
    val writeLocalEvent: Boolean,
    val registerWithYouTube: Boolean,
) {
    companion object {
        fun of(
            totalPlayTimeMs: Long,
            historyDurationMs: Long,
            historyPaused: Boolean,
            counts: Boolean,
            mediaId: String? = null,
        ): PlaybackStatsPlan {
            val longEnough = counts && totalPlayTimeMs >= historyDurationMs
            return PlaybackStatsPlan(
                writeLocalEvent = longEnough && !historyPaused,
                // SARASWATI: Phase 5 Task 2 — a song on this phone counts in local history only; YouTube never hears of it.
                registerWithYouTube = longEnough && !LocalSongIds.isLocal(mediaId),
            )
        }
    }
}

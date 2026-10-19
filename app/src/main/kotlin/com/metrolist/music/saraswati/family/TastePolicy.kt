/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 13 — whose taste a play shapes. A song someone else picked in a Sangat, and the
// shared Parivaar Mix, are not what this person chose, so by default they leave no listen history and no
// YouTube watch history behind. The Saraswati setting CountSharedPlaysKey turns them on.
package com.metrolist.music.saraswati.family

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
     * The host's own tracks carry no `suggestedBy`; a passenger's carry the passenger's name. A guest owns only
     * a track carrying its own name. A Sangat takes precedence over the Parivaar Mix queue the phone may still
     * hold.
     */
    fun resolve(
        inRoom: Boolean,
        isHost: Boolean,
        myUsername: String,
        suggestedBy: String?,
        queueIsParivaarMix: Boolean,
    ): PlayContext {
        if (inRoom) {
            val by = suggestedBy?.trim().orEmpty()
            val me = myUsername.trim()
            val isMine = me.isNotEmpty() && by.equals(me, ignoreCase = true)
            val addedBySelf = if (isHost) by.isEmpty() || isMine else isMine
            return PlayContext.Sangat(addedBySelf)
        }
        return if (queueIsParivaarMix) PlayContext.ParivaarMix else PlayContext.Solo
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
        ): PlaybackStatsPlan {
            val longEnough = counts && totalPlayTimeMs >= historyDurationMs
            return PlaybackStatsPlan(
                writeLocalEvent = longEnough && !historyPaused,
                registerWithYouTube = longEnough,
            )
        }
    }
}

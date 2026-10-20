/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — what Saral knows about the player, in the
// few words the screen needs. The screen never reports what it asked for, only
// what the player is doing: the adapter (PlayerConnectionPlayback) builds this
// from the real player, and the view model's status rules read nothing else.
package com.metrolist.music.saraswati.saral

import java.io.IOException
import java.nio.channels.UnresolvedAddressException

/** Why nothing is playing as asked: no signal, or something else. */
enum class PlaybackProblem { OFFLINE, COULD_NOT_PLAY }

/**
 * [mediaId] and [title] are the track the player has now. [playing] is true while it plays or
 * is about to (the big button shows pause). [ours] is true when that track belongs to the last
 * queue Saral started. [problem] is set while the queue Saral started could not load, or the
 * player is failing or waiting for a connection.
 */
data class SaralPlayerState(
    val mediaId: String? = null,
    val title: String? = null,
    val playing: Boolean = false,
    val ours: Boolean = false,
    val problem: PlaybackProblem? = null,
)

object PlaybackFailures {
    /** True if [failure], or anything it was caused by, is a network failure. */
    fun isOffline(failure: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.add(current)) {
            if (current is IOException || current is UnresolvedAddressException) return true
            current = current.cause
        }
        return false
    }

    fun problemFor(failure: Throwable): PlaybackProblem = if (isOffline(failure)) PlaybackProblem.OFFLINE else PlaybackProblem.COULD_NOT_PLAY
}

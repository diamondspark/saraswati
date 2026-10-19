/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 14 — letting other family members add songs to a family playlist is YouTube's
// collaborative-playlist feature, whose calls are not confirmed yet (Task 23 captures them). Until then this is
// the seam: [UnconfirmedCollaborationApi] says "not available" to everything, so a family playlist is read-only
// for everyone but the phone that created it.
package com.metrolist.music.saraswati.family.playlists

/** Collaboration is not available (yet), so this phone may not add to someone else's playlist. */
object CollaborationUnavailable : Exception("collaboration on family playlists is not available")

interface CollaborationApi {
    /** Turns collaboration on for a playlist this phone owns and returns the invite others join with. */
    suspend fun enable(playlistId: String): Result<String>

    /** Joins a playlist as a collaborator with the invite its owner shared. */
    suspend fun join(
        playlistId: String,
        invite: String,
    ): Result<Unit>
}

object UnconfirmedCollaborationApi : CollaborationApi {
    override suspend fun enable(playlistId: String): Result<String> = Result.failure(CollaborationUnavailable)

    override suspend fun join(
        playlistId: String,
        invite: String,
    ): Result<Unit> = Result.failure(CollaborationUnavailable)
}

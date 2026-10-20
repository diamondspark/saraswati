/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — the two hub calls the Parivaar code makes, as an interface so it runs against a
// fake in plain JUnit (HubClient is final and these are not on HubApi). The real one delegates to HubClient, whose
// requests are string bodies, so Content-Length is always set.
package com.metrolist.music.saraswati.family.parivaar

import com.metrolist.music.saraswati.family.HubClient
import com.metrolist.music.saraswati.family.ParivaarPlaylist

interface ParivaarHub {
    /** Sets this phone's own row: its Parivaar playlist id and the name the family sees. */
    suspend fun register(
        sessionToken: String,
        playlistId: String,
        memberName: String,
    ): Result<Unit>

    /** Every approved member's registered playlist, this phone's included. */
    suspend fun playlists(sessionToken: String): Result<List<ParivaarPlaylist>>
}

class HubClientParivaarHub(
    private val hub: HubClient,
) : ParivaarHub {
    override suspend fun register(
        sessionToken: String,
        playlistId: String,
        memberName: String,
    ): Result<Unit> = hub.putParivaarPlaylist(sessionToken, playlistId, memberName).map { }

    override suspend fun playlists(sessionToken: String): Result<List<ParivaarPlaylist>> = hub.getParivaarPlaylists(sessionToken)
}

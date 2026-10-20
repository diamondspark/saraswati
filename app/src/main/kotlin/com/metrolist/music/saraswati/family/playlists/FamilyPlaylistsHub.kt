/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 14 — the three hub calls the family playlists make, as an interface so the repository
// runs against a fake in plain JUnit (HubClient is final and these are not on HubApi). The real one delegates to
// HubClient, whose requests are string bodies, so Content-Length is always set.
package com.metrolist.music.saraswati.family.playlists

import com.metrolist.music.saraswati.family.FamilyPlaylist
import com.metrolist.music.saraswati.family.HubClient

interface FamilyPlaylistsHub {
    suspend fun list(sessionToken: String): Result<List<FamilyPlaylist>>

    suspend fun add(
        sessionToken: String,
        playlistId: String,
        title: String,
    ): Result<FamilyPlaylist>

    /** The phone that added the playlist may remove it; any other phone gets a 403. */
    suspend fun remove(
        sessionToken: String,
        id: String,
    ): Result<Unit>
}

class HubClientFamilyPlaylistsHub(
    private val hub: HubClient,
) : FamilyPlaylistsHub {
    override suspend fun list(sessionToken: String): Result<List<FamilyPlaylist>> = hub.getFamilyPlaylists(sessionToken)

    override suspend fun add(
        sessionToken: String,
        playlistId: String,
        title: String,
    ): Result<FamilyPlaylist> = hub.addFamilyPlaylist(sessionToken, playlistId, title)

    override suspend fun remove(
        sessionToken: String,
        id: String,
    ): Result<Unit> = hub.removeFamilyPlaylist(sessionToken, id)
}

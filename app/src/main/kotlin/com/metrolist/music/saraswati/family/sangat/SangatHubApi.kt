/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — the two hub calls the Sangat watcher and car mode make, as a small interface so
// they run against a plain fake in JUnit. HubClient is the only implementation; HubApi is left as it was.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.LiveSangatRoom

interface SangatHubApi {
    suspend fun getLiveSangat(sessionToken: String): Result<List<LiveSangatRoom>>

    suspend fun setSangatCarMode(
        sessionToken: String,
        roomCode: String,
        carMode: Boolean,
    ): Result<Unit>
}

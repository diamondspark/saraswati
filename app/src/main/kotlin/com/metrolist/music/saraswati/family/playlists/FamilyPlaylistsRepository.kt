/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 14 — the family's shared YouTube playlists: the hub holds the registry (who added which
// playlist), YouTube holds the songs.
//
// Before Task 23 confirms YouTube's collaboration calls, everyone in the family can see and play every playlist
// but only the phone that created it can add songs ([addSong] fails with [CollaborationUnavailable] for anyone
// else). create() asks for collaboration after registering and carries on when it is refused, which leaves the
// playlist read-only for the others.
//
// Switched off by the owner (family_playlists): the list reads as empty and nothing is sent to the hub.
// A playlist made on YouTube whose registration then fails is deleted again, so a failed create leaves nothing
// behind. Nothing here throws except to pass on a cancellation.
package com.metrolist.music.saraswati.family.playlists

import com.metrolist.music.saraswati.family.FamilyPlaylist
import com.metrolist.music.saraswati.family.HubException
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.parivaar.YouTubePlaylistPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** The owner has switched family playlists off. */
class FamilyPlaylistsDisabled : Exception("family playlists are switched off")

/** This phone has no hub session yet. */
class FamilyPlaylistsNotPaired : Exception("this phone is not paired with the family hub")

class FamilyPlaylistsRepository(
    private val hub: FamilyPlaylistsHub,
    private val port: YouTubePlaylistPort,
    private val identity: HubIdentity,
    private val cache: FamilyPlaylistsCache,
    private val collaboration: CollaborationApi = UnconfirmedCollaborationApi,
    private val enabled: () -> Boolean = { true },
) {
    // Create, remove and refresh each read the list and write it back; one at a time so a refresh that
    // started before a create cannot put the older list over the newer one.
    private val changes = Mutex()

    /** The family's playlists, oldest first; empty while the feature is off. */
    val playlists: Flow<List<FamilyPlaylist>> = cache.playlists.map { if (enabled()) it else emptyList() }

    fun cached(): List<FamilyPlaylist> = if (enabled()) cache.cached() else emptyList()

    /** Asks the hub for the list and keeps it. Keeps the old list on any failure. */
    suspend fun refresh() {
        if (!enabled()) return
        changes.withLock {
            val token = identity.sessionToken() ?: return
            val fetched = hub.list(token).getOrNull() ?: return
            safely { cache.replace(fetched) }
        }
    }

    suspend fun create(title: String): Result<FamilyPlaylist> {
        if (!enabled()) return Result.failure(FamilyPlaylistsDisabled())
        val clean = title.trim().take(MAX_TITLE_LENGTH)
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("a playlist needs a name"))
        return changes.withLock {
            val token = identity.sessionToken() ?: return@withLock Result.failure(FamilyPlaylistsNotPaired())
            val playlistId = port.createUnlisted(clean).getOrElse { return@withLock Result.failure(it) }
            val registered = hub.add(token, playlistId, clean)
            val playlist =
                registered.getOrElse { failure ->
                    // Registered nowhere, so nobody could ever find it: take it off YouTube again.
                    port.delete(playlistId)
                    return@withLock Result.failure(failure)
                }
            safely { cache.replace(cache.cached().filterNot { it.id == playlist.id } + playlist) }
            // Others can add only if YouTube lets them; a refusal just leaves the playlist read-only for them.
            collaboration.enable(playlistId)
            Result.success(playlist)
        }
    }

    suspend fun addSong(
        p: FamilyPlaylist,
        videoId: String,
    ): Result<Unit> {
        if (!enabled()) return Result.failure(FamilyPlaylistsDisabled())
        val own = identity.deviceId() ?: return Result.failure(FamilyPlaylistsNotPaired())
        if (p.addedByDeviceId != own) return Result.failure(CollaborationUnavailable)
        return port.add(p.playlistId, videoId)
    }

    /**
     * Takes the playlist off the family's list. Only the phone that added it can (the hub answers 403 to the
     * rest). The playlist itself stays on the creator's YouTube account, with its songs.
     */
    suspend fun remove(p: FamilyPlaylist): Result<Unit> {
        if (!enabled()) return Result.failure(FamilyPlaylistsDisabled())
        return changes.withLock {
            val token = identity.sessionToken() ?: return@withLock Result.failure(FamilyPlaylistsNotPaired())
            val removed = hub.remove(token, p.id)
            // Already gone from the hub is as good as removed.
            val gone = removed.isSuccess || (removed.exceptionOrNull() as? HubException)?.status == 404
            if (!gone) return@withLock removed
            safely { cache.replace(cache.cached().filterNot { it.id == p.id }) }
            Result.success(Unit)
        }
    }

    // The cache is a convenience: a failing disk write must not turn a done hub change into a failure.
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The in-memory list was already updated; the next refresh writes it again.
        }
    }

    companion object {
        /** The hub rejects longer titles. */
        const val MAX_TITLE_LENGTH = 150
    }
}

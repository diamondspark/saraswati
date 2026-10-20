/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — fakes for the YouTube port, the hub, the phone's identity and its top songs.
package com.metrolist.music.saraswati.family.parivaar

import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.ParivaarPlaylist
import java.time.Instant

fun track(id: String) = MixTrack(id, "Title $id", "Artist $id", null, 180_000L)

class FakeIdentity(
    var token: String? = "session-token",
    var device: String? = "dev-me",
) : HubIdentity {
    override suspend fun sessionToken() = token

    override suspend fun deviceId() = device
}

/** A YouTube account in memory: playlists by id, each a list of entries, plus a record of every call in order. */
class FakePort : YouTubePlaylistPort {
    val playlists = LinkedHashMap<String, MutableList<PlaylistEntry>>()
    val calls = mutableListOf<String>()
    val createdTitles = mutableListOf<String>()
    var failCreate: Throwable? = null
    var failAdd: Throwable? = null
    val rejectedSongs = mutableSetOf<String>()
    val failEntries = HashMap<String, Throwable>()
    val radioSeeds = mutableListOf<String>()
    var radioTracks: List<MixTrack> = emptyList()
    private var next = 0

    override suspend fun createUnlisted(title: String): Result<String> {
        calls += "create"
        failCreate?.let { return Result.failure(it) }
        createdTitles += title
        val id = "PLfake${"%06d".format(++next)}"
        playlists[id] = mutableListOf()
        return Result.success(id)
    }

    override suspend fun entries(playlistId: String): Result<List<PlaylistEntry>> {
        calls += "entries:$playlistId"
        failEntries[playlistId]?.let { return Result.failure(it) }
        val list = playlists[playlistId] ?: return Result.failure(PlaylistGoneException(playlistId))
        return Result.success(list.toList())
    }

    override suspend fun add(
        playlistId: String,
        videoId: String,
    ): Result<Unit> {
        calls += "add:$videoId"
        failAdd?.let { return Result.failure(it) }
        if (videoId in rejectedSongs) return Result.failure(SongRejectedException(videoId))
        playlists[playlistId]?.add(PlaylistEntry("set-$videoId", track(videoId)))
        return Result.success(Unit)
    }

    override suspend fun remove(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ): Result<Unit> {
        calls += "remove:$videoId"
        playlists[playlistId]?.removeAll { it.setVideoId == setVideoId }
        return Result.success(Unit)
    }

    override suspend fun delete(playlistId: String): Result<Unit> {
        calls += "delete"
        playlists.remove(playlistId)
        return Result.success(Unit)
    }

    override suspend fun radio(seedVideoId: String): Result<List<MixTrack>> {
        radioSeeds += seedVideoId
        return Result.success(radioTracks)
    }

    fun seed(
        playlistId: String,
        vararg ids: String,
    ) {
        playlists[playlistId] = ids.map { PlaylistEntry("set-$it", track(it)) }.toMutableList()
    }

    fun ids(playlistId: String): List<String> = playlists.getValue(playlistId).map { it.videoId }
}

class FakeHub : ParivaarHub {
    val registered = mutableListOf<Triple<String, String, String>>()
    var rows: List<ParivaarPlaylist> = emptyList()
    var failRegister: Throwable? = null
    var failList: Throwable? = null
    var listCalls = 0

    override suspend fun register(
        sessionToken: String,
        playlistId: String,
        memberName: String,
    ): Result<Unit> {
        failRegister?.let { return Result.failure(it) }
        registered += Triple(sessionToken, playlistId, memberName)
        return Result.success(Unit)
    }

    override suspend fun playlists(sessionToken: String): Result<List<ParivaarPlaylist>> {
        listCalls++
        failList?.let { return Result.failure(it) }
        return Result.success(rows)
    }

    fun row(
        deviceId: String,
        name: String,
        playlistId: String,
    ) = ParivaarPlaylist(deviceId, name, playlistId, "2026-10-19T00:00:00Z")
}

class FakeTopSongs(
    var songs: List<RankedSong> = emptyList(),
) : TopSongsSource {
    var lastSince: Instant? = null
    var lastLimit: Int? = null

    override suspend fun top(
        since: Instant,
        limit: Int,
    ): List<RankedSong> {
        lastSince = since
        lastLimit = limit
        return songs
    }
}

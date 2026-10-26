/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 fix wave (Task 19 mockup conformance) — what the Sangat screens show about the room this phone
// is in: who hosts, who is listening, the current song and where it is. Read from the room state the client already
// keeps; display only. The rules for "added by" and the section rows are plain functions so they are unit tested.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.listentogether.RoomState
import com.metrolist.music.listentogether.TrackInfo
import com.metrolist.music.saraswati.family.LiveSangatRoom

data class SangatMember(
    val name: String,
    val isMe: Boolean,
)

data class SangatRoomInfo(
    val roomCode: String,
    val hostName: String,
    val isHost: Boolean,
    /** Connected people, the host first. */
    val members: List<SangatMember>,
    val track: TrackInfo?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val queue: List<TrackInfo> = emptyList(),
) {
    val listening: Int get() = members.size

    /** People in the room other than this phone's owner. */
    val others: List<String> get() = members.filterNot { it.isMe }.map { it.name }

    companion object {
        fun from(
            state: RoomState?,
            myUserId: String?,
        ): SangatRoomInfo? {
            if (state == null || state.roomCode.isEmpty()) return null
            val connected = state.usersList.filter { it.isConnected }
            val host = connected.firstOrNull { it.userId == state.hostId }
            val ordered = listOfNotNull(host) + connected.filter { it.userId != state.hostId }
            return SangatRoomInfo(
                roomCode = state.roomCode,
                hostName = (host ?: state.usersList.firstOrNull { it.userId == state.hostId })?.username.orEmpty(),
                isHost = myUserId != null && state.hostId == myUserId,
                members = ordered.map { SangatMember(it.username, isMe = myUserId != null && it.userId == myUserId) },
                track = if (state.hasCurrentTrack()) state.currentTrack else null,
                isPlaying = state.isPlaying,
                positionMs = state.position,
                queue = state.queueList,
            )
        }
    }
}

/** One row of the Sangat section's live list: a room and whether this phone is already in it. */
data class SangatRow(
    val room: LiveSangatRoom,
    val joined: Boolean,
)

/** The Home banner: the live room it names, whether this phone is in it, and who it plays in sync with. */
data class SangatBanner(
    val room: LiveSangatRoom,
    val joined: Boolean,
    val syncedWith: List<String>,
    /** Joined to a car room, where nothing plays on this phone: the banner says it adds songs, not that it plays in sync. */
    val remote: Boolean = false,
)

object SangatDisplay {
    private const val SONG_TITLE_MAX = 60

    /** The song on now for a live row or the Home banner, or null: single-line, trimmed and cut short. */
    fun songTitle(room: LiveSangatRoom): String? {
        val clean = room.nowPlaying?.title.orEmpty().replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
        if (clean.isEmpty()) return null
        return if (clean.length <= SONG_TITLE_MAX) clean else clean.take(SONG_TITLE_MAX - 1).trimEnd() + "…"
    }

    /** Who added a song: this phone's owner (shown as "You"), the adder named on it, or the host when none is named. */
    fun addedBy(
        suggestedBy: String,
        hostName: String,
        myName: String,
    ): Adder {
        val named = suggestedBy.trim()
        if (named.isEmpty()) return Adder(hostName, isMe = false)
        val mine = myName.isNotBlank() && named.equals(myName.trim(), ignoreCase = true)
        return Adder(named, mine)
    }

    /** The songs after the current one: the room's queue, minus anything up to and including the playing song. */
    fun upNext(
        queue: List<TrackInfo>,
        currentId: String?,
    ): List<TrackInfo> {
        val at = if (currentId == null) -1 else queue.indexOfFirst { it.id == currentId }
        return if (at < 0) queue else queue.drop(at + 1)
    }

    fun initial(name: String): String =
        name
            .trim()
            .firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()
            ?.toString() ?: "?"

    /** 0 to 3: which of the four mockup avatar colours a name gets. Stable for a name. */
    fun colorIndex(name: String): Int = Math.floorMod(name.trim().lowercase().hashCode(), 4)

    /** m:ss, as the mockup's progress row shows it. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun progress(
        positionMs: Long,
        durationMs: Long,
    ): Float = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /**
     * The live list with this phone's own hosted room taken out (the hosting card covers it). A room this phone
     * joined is marked, and added from [info] when the hub's list does not show it yet, so Leave is always there.
     */
    fun rows(
        live: List<LiveSangatRoom>,
        info: SangatRoomInfo?,
        remoteController: Boolean,
    ): List<SangatRow> {
        val here = info?.roomCode
        val others = live.filterNot { it.roomCode == here && info?.isHost == true }
        // A remote controller is in a car room even when the list has not caught up with the car mark yet.
        val rows = others.map { if (it.roomCode == here) SangatRow(it.copy(carMode = it.carMode || remoteController), joined = true) else SangatRow(it, joined = false) }
        if (info == null || info.isHost || rows.any { it.joined }) return rows
        val synthesized =
            LiveSangatRoom(
                roomCode = info.roomCode,
                hostName = info.hostName,
                hostDeviceId = "",
                carMode = remoteController,
                memberCount = info.listening,
                startedAt = "",
            )
        return listOf(SangatRow(synthesized, joined = true)) + rows
    }

    /**
     * The Home banner: the room this phone joined (if it is not hosting), else the first live room. Nothing while
     * this phone hosts its own room.
     */
    fun banner(
        live: List<LiveSangatRoom>,
        info: SangatRoomInfo?,
        remoteController: Boolean,
    ): SangatBanner? {
        if (info?.isHost == true) return null
        if (info == null) return live.firstOrNull()?.let { SangatBanner(it, joined = false, syncedWith = emptyList()) }
        val room = rows(live, info, remoteController).first { it.joined }.room
        return SangatBanner(room, joined = true, syncedWith = info.others, remote = room.carMode || remoteController)
    }

}

data class Adder(
    val name: String,
    val isMe: Boolean,
)

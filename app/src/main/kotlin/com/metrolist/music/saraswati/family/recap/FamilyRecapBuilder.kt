/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 14 — builds the week's recap from two snapshots, the same way on every phone.
//
//  - Anthem: the song on the most members' lists (two or more); ties go to the lowest rank sum, then video id.
//  - Favourites: each member's rank-one song, by member name.
//  - Shared with me: songs each other member has in common with this phone's list, zeros dropped, most first.
//  - New this week: each member's songs not on their list in the previous snapshot, top three by rank.
//
// Fewer than two members with songs gives no recap. Only members in the current snapshot appear, so a member who
// has left the family drops out with the snapshot that lacks them.
package com.metrolist.music.saraswati.family.recap

import com.metrolist.music.saraswati.family.parivaar.MemberTopSongs
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import java.time.LocalDate

object FamilyRecapBuilder {
    const val NEW_MAX = 3

    fun build(
        current: RecapSnapshot,
        previous: RecapSnapshot?,
        myDeviceId: String,
        today: LocalDate,
    ): FamilyRecap? {
        val members = current.members.filter { it.tracks.isNotEmpty() }.sortedBy { it.memberName }
        if (members.size < 2) return null
        val mine = members.firstOrNull { it.deviceId == myDeviceId }?.tracks?.map { it.videoId }?.toSet().orEmpty()
        val anthem = anthem(members)

        return FamilyRecap(
            weekOf = today,
            anthem = anthem?.first,
            anthemMembers = anthem?.second ?: 0,
            favourites = members.map { MemberFavourite(it.memberName, it.tracks.first()) },
            sharedWithMe =
                members
                    .filter { it.deviceId != myDeviceId }
                    .map { SharedCount(it.memberName, it.tracks.count { track -> track.videoId in mine }) }
                    .filter { it.count > 0 }
                    .sortedByDescending { it.count },
            newThisWeek = newThisWeek(members, previous),
        )
    }

    /** The song on the most lists with its list count, or null when no song is on two lists. */
    private fun anthem(members: List<MemberTopSongs>): Pair<MixTrack, Int>? {
        val shared = HashMap<String, Shared>()
        for (member in members) {
            val seen = HashSet<String>()
            member.tracks.forEachIndexed { rank, track ->
                if (seen.add(track.videoId)) {
                    val entry = shared.getOrPut(track.videoId) { Shared(track) }
                    entry.lists++
                    entry.rankSum += rank
                }
            }
        }
        val best =
            shared.values
                .filter { it.lists >= 2 }
                .minWithOrNull(compareBy<Shared>({ -it.lists }, { it.rankSum }, { it.track.videoId }))
                ?: return null
        return best.track to best.lists
    }

    private fun newThisWeek(
        members: List<MemberTopSongs>,
        previous: RecapSnapshot?,
    ): List<MemberNew> {
        if (previous == null) return emptyList()
        val before = previous.members.associate { member -> member.deviceId to member.tracks.map { it.videoId }.toSet() }
        return members.mapNotNull { member ->
            val known = before[member.deviceId].orEmpty()
            val fresh = member.tracks.filter { it.videoId !in known }.take(NEW_MAX)
            if (fresh.isEmpty()) null else MemberNew(member.memberName, fresh)
        }
    }

    private class Shared(
        val track: MixTrack,
        var lists: Int = 0,
        var rankSum: Int = 0,
    )
}

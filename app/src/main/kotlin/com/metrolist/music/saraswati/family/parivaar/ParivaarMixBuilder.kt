/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 11 — builds the Parivaar Mix, the same way on every phone for the same input.
//
//  1. Overlap: songs in two or more members' lists, most members first, then lowest rank sum, then video id.
//  2. Turns: members in device id order each add their next song not yet in the mix, until every list is spent
//     or the mix holds MIX_MAX - RADIO_MAX songs. The overlap counts toward that.
//  3. Radio: songs from the caller's radio list not already in the mix, at most RADIO_MAX, up to MIX_MAX.
//
// Fewer than two members with songs gives an empty mix, radio included. Radio is not gated on [radioSeed]
// here: the caller asks for radio only when there is a seed, and whatever it passes is used.
package com.metrolist.music.saraswati.family.parivaar

object ParivaarMixBuilder {
    const val MIX_MAX = 50
    const val RADIO_MAX = 10
    private const val MEMBER_MAX = MIX_MAX - RADIO_MAX

    /** The video id to start the radio from: the first overlap song, or null when no song is shared. */
    fun radioSeed(members: List<MemberTopSongs>): String? = overlap(members.filter { it.tracks.isNotEmpty() }).firstOrNull()?.videoId

    fun build(
        members: List<MemberTopSongs>,
        radio: List<MixTrack>,
    ): List<MixTrack> {
        val active = members.filter { it.tracks.isNotEmpty() }.sortedBy { it.deviceId }
        if (active.size < 2) return emptyList()

        val mix = LinkedHashMap<String, MixTrack>()
        for (track in overlap(active)) {
            if (mix.size >= MEMBER_MAX) break
            mix[track.videoId] = track
        }

        val next = IntArray(active.size)
        var spent = false
        while (mix.size < MEMBER_MAX && !spent) {
            spent = true
            for ((i, member) in active.withIndex()) {
                if (mix.size >= MEMBER_MAX) break
                var j = next[i]
                while (j < member.tracks.size && member.tracks[j].videoId in mix) j++
                if (j < member.tracks.size) {
                    mix[member.tracks[j].videoId] = member.tracks[j]
                    next[i] = j + 1
                    spent = false
                } else {
                    next[i] = j
                }
            }
        }

        var radioAdded = 0
        for (track in radio) {
            if (radioAdded >= RADIO_MAX || mix.size >= MIX_MAX) break
            if (track.videoId !in mix) {
                mix[track.videoId] = track
                radioAdded++
            }
        }
        return mix.values.toList()
    }

    private class Shared(
        val track: MixTrack,
        var members: Int = 0,
        var rankSum: Int = 0,
    )

    /** Songs in two or more members' lists, in mix order. A song repeated within one list counts once, at its best rank. */
    private fun overlap(members: List<MemberTopSongs>): List<MixTrack> {
        val shared = HashMap<String, Shared>()
        for (member in members.sortedBy { it.deviceId }) {
            val seen = HashSet<String>()
            member.tracks.forEachIndexed { rank, track ->
                if (seen.add(track.videoId)) {
                    val entry = shared.getOrPut(track.videoId) { Shared(track) }
                    entry.members++
                    entry.rankSum += rank
                }
            }
        }
        return shared.values
            .filter { it.members >= 2 }
            .sortedWith(compareByDescending<Shared> { it.members }.thenBy { it.rankSum }.thenBy { it.track.videoId })
            .map { it.track }
    }
}

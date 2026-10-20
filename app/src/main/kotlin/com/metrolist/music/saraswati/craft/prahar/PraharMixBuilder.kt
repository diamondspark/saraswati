/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 6 — the Prahar mix as a pure function. Seeds come only from this person's own plays inside
// the current window; every other song comes from YouTube radio. Local songs (local: ids) never seed radio and never
// enter a mix. Same input, same output.
package com.metrolist.music.saraswati.craft.prahar

import com.metrolist.music.saraswati.family.parivaar.MixTrack
import java.time.LocalDate

object PraharMixBuilder {
    const val LOOKBACK_DAYS = 60
    const val OWN_MAX = 8
    const val MIN_OWN = 5
    const val MIX_MAX = 25

    private const val LOCAL_PREFIX = "local:"
    private const val SEED_POOL = 3

    /**
     * The person's own songs for [prahar]: plays inside the window's hours only, most distinct days first, then most
     * play time, then song id. Snoozed and local songs are dropped.
     */
    fun ownSongs(
        plays: List<PraharPlay>,
        prahar: Prahar,
        snoozed: Set<String>,
    ): List<String> =
        plays
            .asSequence()
            .filter { Prahar.of(it.at.toLocalTime()) == prahar }
            .filter { it.songId !in snoozed && !it.songId.startsWith(LOCAL_PREFIX) }
            .groupBy { it.songId }
            .map { (songId, rows) ->
                Triple(songId, rows.map { it.at.toLocalDate() }.distinct().size, rows.sumOf { it.playTimeMs })
            }.sortedWith(
                compareByDescending<Triple<String, Int, Long>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first },
            ).take(OWN_MAX)
            .map { it.first }

    /** The day's seed: rotates among the top three own songs. */
    fun radioSeed(
        own: List<String>,
        day: LocalDate,
    ): String? =
        if (own.isEmpty()) null else own[day.dayOfYear % minOf(SEED_POOL, own.size)]

    /**
     * Own, radio, radio, own, radio, radio, ... until both run out or [MIX_MAX] is reached. Deduplicated by videoId.
     * Fewer than [MIN_OWN] own songs (after snoozed ones are dropped) gives an empty mix.
     */
    fun build(
        own: List<MixTrack>,
        radio: List<MixTrack>,
        snoozed: Set<String>,
    ): List<MixTrack> {
        val ownTracks = own.usable(snoozed).distinctBy { it.videoId }
        if (ownTracks.size < MIN_OWN) return emptyList()
        val ownIds = ownTracks.map { it.videoId }.toSet()
        val radioTracks = radio.usable(snoozed).filter { it.videoId !in ownIds }.distinctBy { it.videoId }

        val out = ArrayList<MixTrack>(MIX_MAX)
        var ownIndex = 0
        var radioIndex = 0
        out += ownTracks[ownIndex++]
        while (out.size < MIX_MAX && (ownIndex < ownTracks.size || radioIndex < radioTracks.size)) {
            repeat(2) {
                if (radioIndex < radioTracks.size && out.size < MIX_MAX) out += radioTracks[radioIndex++]
            }
            if (ownIndex < ownTracks.size && out.size < MIX_MAX) out += ownTracks[ownIndex++]
        }
        return out
    }

    private fun List<MixTrack>.usable(snoozed: Set<String>): List<MixTrack> =
        filter { it.videoId.isNotBlank() && !it.videoId.startsWith(LOCAL_PREFIX) && it.videoId !in snoozed }
}

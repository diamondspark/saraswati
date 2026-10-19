/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 11 — one song in a Parivaar Mix, and one family member's top songs going into it.
package com.metrolist.music.saraswati.family.parivaar

import kotlinx.serialization.Serializable

@Serializable
data class MixTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long?,
)

/** One member's most-played songs, best first. */
data class MemberTopSongs(
    val deviceId: String,
    val memberName: String,
    val tracks: List<MixTrack>,
)

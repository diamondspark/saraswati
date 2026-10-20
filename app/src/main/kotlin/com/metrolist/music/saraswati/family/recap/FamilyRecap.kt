/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 14 — what Parivaar Saar shows: one week's recap of the family's shared top-songs lists,
// computed on this phone from the snapshots it holds. Nothing here leaves the phone.
package com.metrolist.music.saraswati.family.recap

import com.metrolist.music.saraswati.family.parivaar.MixTrack
import java.time.LocalDate

data class FamilyRecap(
    val weekOf: LocalDate,
    val anthem: MixTrack?,
    val anthemMembers: Int,
    val favourites: List<MemberFavourite>,
    val sharedWithMe: List<SharedCount>,
    val newThisWeek: List<MemberNew>,
)

data class MemberFavourite(
    val memberName: String,
    val track: MixTrack,
)

data class SharedCount(
    val memberName: String,
    val count: Int,
)

data class MemberNew(
    val memberName: String,
    val tracks: List<MixTrack>,
)

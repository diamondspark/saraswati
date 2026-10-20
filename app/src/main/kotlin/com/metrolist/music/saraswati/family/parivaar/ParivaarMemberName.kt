/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: final fix wave (M1) — the name a member goes by in the family's Parivaar list and in the title of
// their YouTube playlist ("Saraswati · Mummy top songs"): their Sangat display name, and the phone model only
// when they have not set one.
package com.metrolist.music.saraswati.family.parivaar

object ParivaarMemberName {
    fun of(
        sangatName: String?,
        deviceModel: String,
    ): String = sangatName?.trim().orEmpty().ifBlank { deviceModel }
}

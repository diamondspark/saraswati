/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 8 — the four family features the owner can switch off for
// everyone from the hub (the kill-switch keys the Worker serves). A feature
// is on unless the hub says otherwise, and stays on when the hub cannot be
// reached: a kill-switch that fails closed would take features away because
// a server was down.
package com.metrolist.music.saraswati.family

enum class FamilyFeature(
    val key: String,
) {
    SANGAT("sangat"),
    PARIVAAR_MIX("parivaar_mix"),
    FAMILY_PLAYLISTS("family_playlists"),
    CAST_RELAY("cast_relay"),
    ;

    companion object {
        fun fromKey(key: String): FamilyFeature? = entries.firstOrNull { it.key == key }

        fun isEnabled(
            killSwitches: Map<String, Boolean>,
            feature: FamilyFeature,
        ): Boolean = killSwitches[feature.key] ?: true
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — carries out one intent the hub chose, using only what this phone already has. An
// unknown station, an empty or switched-off Parivaar Mix, or a switched-off Sangat becomes a search for the
// question. Sangat only opens its section; it never starts a room.
package com.metrolist.music.saraswati.ask

import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore

/** What the Ask screens may do in the app. The screens implement it; the executor only calls it. */
interface AskActions {
    fun openSearch(query: String)

    fun playStation(station: Station)

    fun playParivaarMix(tracks: List<MixTrack>)

    fun openSangat()
}

sealed interface ExecResult {
    data object Done : ExecResult

    data class ShowAnswer(
        val text: String,
    ) : ExecResult
}

class AskExecutor(
    private val actions: AskActions,
    private val stations: StationsCache,
    private val mixStore: ParivaarMixStore,
    private val gate: FamilyFeatureGate,
) {
    fun execute(
        intent: AskIntent,
        question: String,
    ): ExecResult =
        when (intent) {
            is AskIntent.Search -> {
                actions.openSearch(intent.query)
                ExecResult.Done
            }
            is AskIntent.PlayStation -> {
                val station = stations.snapshot().firstOrNull { it.id == intent.stationId }
                if (station == null) {
                    actions.openSearch(question)
                } else {
                    actions.playStation(station)
                }
                ExecResult.Done
            }
            AskIntent.ParivaarMix -> {
                val tracks = mixStore.cached().tracks
                if (gate.isEnabled(FamilyFeature.PARIVAAR_MIX) && tracks.isNotEmpty()) {
                    actions.playParivaarMix(tracks)
                } else {
                    actions.openSearch(question)
                }
                ExecResult.Done
            }
            AskIntent.Sangat -> {
                if (gate.isEnabled(FamilyFeature.SANGAT)) actions.openSangat() else actions.openSearch(question)
                ExecResult.Done
            }
            is AskIntent.Answer -> ExecResult.ShowAnswer(intent.text)
        }
}

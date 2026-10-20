/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — the voice path in Saral, with Ask Saraswati invisible to the person. A spoken
// request goes to the hub with a 3-second cap (no retry); anything but a clear station or Parivaar Mix is the
// same voice search Saral has always done, so an unavailable hub changes nothing the person can see except the
// wait of up to 3 seconds.
package com.metrolist.music.saraswati.ask

import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
import kotlinx.coroutines.withTimeoutOrNull

const val SARAL_TIMEOUT_MS = 3000L

sealed interface SaralAction {
    data class PlayStation(
        val stationId: String,
    ) : SaralAction

    data object ParivaarMix : SaralAction

    /** A search for [text]: the model's query when it chose one, else the spoken words. */
    data class VoiceSearch(
        val text: String,
    ) : SaralAction
}

class SaralAsk(
    private val repo: AskRepository,
    private val stations: StationsCache,
    private val mixStore: ParivaarMixStore,
    private val gate: FamilyFeatureGate,
) {
    suspend fun resolve(spoken: String): SaralAction {
        val outcome = withTimeoutOrNull(SARAL_TIMEOUT_MS) { repo.ask(spoken) } ?: AskOutcome.Unavailable
        val intent = (outcome as? AskOutcome.Reply)?.reply?.intent ?: return SaralAction.VoiceSearch(spoken)
        return when (intent) {
            is AskIntent.Search -> SaralAction.VoiceSearch(intent.query)
            is AskIntent.PlayStation ->
                if (stations.snapshot().any { it.id == intent.stationId }) {
                    SaralAction.PlayStation(intent.stationId)
                } else {
                    SaralAction.VoiceSearch(spoken)
                }
            AskIntent.ParivaarMix ->
                if (cachedMixTracks().isNotEmpty()) SaralAction.ParivaarMix else SaralAction.VoiceSearch(spoken)
            AskIntent.Sangat, is AskIntent.Answer -> SaralAction.VoiceSearch(spoken)
        }
    }

    /** The mix Saral plays for [SaralAction.ParivaarMix]: empty while the feature is off or nothing is cached. */
    suspend fun cachedMixTracks(): List<MixTrack> {
        mixStore.awaitHydrated()
        return if (gate.isEnabled(FamilyFeature.PARIVAAR_MIX)) mixStore.cached().tracks else emptyList()
    }
}

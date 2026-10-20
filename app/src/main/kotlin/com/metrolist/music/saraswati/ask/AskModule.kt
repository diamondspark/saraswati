/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — wiring for Ask Saraswati: the hub client is the only AskHub, and the clock is the
// phone's own, used only to tell a real daily limit from a bad one.
package com.metrolist.music.saraswati.ask

import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubClient
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AskModule {
    @Provides
    @Singleton
    fun provideAskRepository(
        hub: HubClient,
        identity: HubIdentity,
        gate: FamilyFeatureGate,
    ): AskRepository = AskRepository(hub, identity, gate, Clock.systemUTC())

    @Provides
    @Singleton
    fun provideSaralAsk(
        repo: AskRepository,
        stations: StationsCache,
        mixStore: ParivaarMixStore,
        gate: FamilyFeatureGate,
    ): SaralAsk = SaralAsk(repo, stations, mixStore, gate)
}

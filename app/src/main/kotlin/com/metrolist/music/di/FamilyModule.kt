/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — wiring for the family hub screens. HubClient is the
// only HubApi; its OkHttp client carries a short connect and read timeout so
// an unreachable hub fails a screen's action in seconds, not minutes.
package com.metrolist.music.di

import android.content.Context
import com.metrolist.music.BuildConfig
import com.metrolist.music.saraswati.family.DataStoreFamilyStore
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.FamilyStore
import com.metrolist.music.saraswati.family.FamilyStoreHubIdentity
import com.metrolist.music.saraswati.family.HubApi
import com.metrolist.music.saraswati.family.HubClient
import com.metrolist.music.saraswati.family.HubConfig
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.sangat.SangatCarMode
import com.metrolist.music.saraswati.family.sangat.SangatForeground
import com.metrolist.music.saraswati.family.sangat.SangatLiveWatcher
import com.metrolist.music.saraswati.security.SaraswatiVault
import com.metrolist.music.utils.dataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FamilyModule {
    @Provides
    @Singleton
    fun provideHubConfig(): HubConfig = HubConfig(BuildConfig.HUB_BASE_URL)

    // SARASWATI: Phase 4 Task 8 — the Phase 4 packages need HubClient's own functions (Sangat, Parivaar,
    // family playlists), so the client is provided as itself and HubApi is that same instance.
    @Provides
    @Singleton
    fun provideHubClient(config: HubConfig): HubClient =
        HubClient(
            config.baseUrl,
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build(),
        )

    @Provides
    @Singleton
    fun provideHubApi(client: HubClient): HubApi = client

    @Provides
    @Singleton
    fun provideHubIdentity(store: FamilyStore): HubIdentity = FamilyStoreHubIdentity(store)

    @Provides
    @Singleton
    fun provideFamilyFeatureGate(
        @ApplicationContext context: Context,
        hub: HubApi,
        identity: HubIdentity,
    ): FamilyFeatureGate = FamilyFeatureGate(hub, identity, context.dataStore, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    // SARASWATI: Phase 4 Task 9 — Sangat's live-room watcher and car mode.
    @Provides
    @Singleton
    fun provideSangatCarMode(
        hub: HubClient,
        identity: HubIdentity,
    ): SangatCarMode = SangatCarMode(hub, identity)

    @Provides
    @Singleton
    fun provideSangatLiveWatcher(
        hub: HubClient,
        identity: HubIdentity,
        gate: FamilyFeatureGate,
    ): SangatLiveWatcher = SangatLiveWatcher(hub, identity, gate)

    // SARASWATI: Phase 4 review fix (M13) — one process-wide foreground count, polling on a scope of its own.
    @Provides
    @Singleton
    fun provideSangatForeground(watcher: SangatLiveWatcher): SangatForeground =
        SangatForeground(watcher, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Provides
    @Singleton
    fun provideFamilyStore(
        @ApplicationContext context: Context,
    ): FamilyStore = DataStoreFamilyStore(context.dataStore, SaraswatiVault.cookieVault)
}

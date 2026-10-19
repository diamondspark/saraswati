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
import com.metrolist.music.saraswati.family.FamilyStore
import com.metrolist.music.saraswati.family.HubApi
import com.metrolist.music.saraswati.family.HubClient
import com.metrolist.music.saraswati.family.HubConfig
import com.metrolist.music.saraswati.security.SaraswatiVault
import com.metrolist.music.utils.dataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FamilyModule {
    @Provides
    @Singleton
    fun provideHubConfig(): HubConfig = HubConfig(BuildConfig.HUB_BASE_URL)

    @Provides
    @Singleton
    fun provideHubApi(config: HubConfig): HubApi =
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
    fun provideFamilyStore(
        @ApplicationContext context: Context,
    ): FamilyStore = DataStoreFamilyStore(context.dataStore, SaraswatiVault.cookieVault)
}

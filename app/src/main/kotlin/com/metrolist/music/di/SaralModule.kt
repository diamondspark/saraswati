/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — wiring for Saral and the caches it shares with the car.
// The stations cache loads on its own scope so building it never blocks the
// thread that asked for it.
package com.metrolist.music.di

import android.content.Context
import com.metrolist.music.saraswati.family.DataStoreStationsCache
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.saral.DataStoreGrandparentRoleStore
import com.metrolist.music.saraswati.saral.GrandparentRoleStore
import com.metrolist.music.saraswati.saral.DataStoreYouTubeSignIn
import com.metrolist.music.saraswati.saral.SaralSearch
import com.metrolist.music.saraswati.saral.YouTubeSaralSearch
import com.metrolist.music.saraswati.saral.YouTubeSignIn
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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SaralModule {
    @Provides
    @Singleton
    fun provideStationsCache(
        @ApplicationContext context: Context,
    ): StationsCache = DataStoreStationsCache(context.dataStore, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @Singleton
    fun provideGrandparentRoleStore(
        @ApplicationContext context: Context,
    ): GrandparentRoleStore = DataStoreGrandparentRoleStore(context.dataStore)

    @Provides
    fun provideSaralSearch(): SaralSearch = YouTubeSaralSearch()

    @Provides
    @Singleton
    fun provideYouTubeSignIn(
        @ApplicationContext context: Context,
    ): YouTubeSignIn = DataStoreYouTubeSignIn(context.dataStore, SaraswatiVault.cookieVault)
}

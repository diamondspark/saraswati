/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 11 — wiring for the nightly smart downloads ("Raat ke Gaane").
package com.metrolist.music.di

import android.content.Context
import com.metrolist.music.constants.SmartDownloadsEnabledKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.family.parivaar.DatabaseTopSongsSource
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
import com.metrolist.music.saraswati.family.parivaar.YouTubePlaylistPort
import com.metrolist.music.saraswati.offline.DatabaseSmartLibrary
import com.metrolist.music.saraswati.offline.DownloadManagerExecutor
import com.metrolist.music.saraswati.offline.SmartDownloadCandidates
import com.metrolist.music.saraswati.offline.SmartDownloadExecutor
import com.metrolist.music.saraswati.offline.SmartDownloadLedger
import com.metrolist.music.saraswati.offline.SmartDownloadRunner
import com.metrolist.music.saraswati.offline.SmartDownloadScheduler
import com.metrolist.music.saraswati.offline.StationOfflineStore
import com.metrolist.music.utils.dataStore
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SmartDownloadModule {
    @Provides
    @Singleton
    fun provideSmartDownloadLedger(
        @ApplicationContext context: Context,
    ): SmartDownloadLedger = SmartDownloadLedger(context.dataStore)

    // The DownloadUtil is asked for lazily: a cold start with nothing to stop never opens the download database.
    @Provides
    @Singleton
    fun provideSmartDownloadExecutor(
        ledger: SmartDownloadLedger,
        downloadUtil: Lazy<DownloadUtil>,
    ): SmartDownloadExecutor = DownloadManagerExecutor(ledger) { downloadUtil.get() }

    @Provides
    @Singleton
    fun provideSmartDownloadRunner(
        @ApplicationContext context: Context,
        ledger: SmartDownloadLedger,
        executor: SmartDownloadExecutor,
        mixStore: ParivaarMixStore,
        stationsCache: StationsCache,
        stationStore: StationOfflineStore,
        port: YouTubePlaylistPort,
        database: MusicDatabase,
        gate: FamilyFeatureGate,
    ): SmartDownloadRunner =
        SmartDownloadRunner(
            ledger = ledger,
            candidates =
                SmartDownloadCandidates(
                    mixStore = mixStore,
                    stationsCache = stationsCache,
                    stationStore = stationStore,
                    port = port,
                    topSongs = DatabaseTopSongsSource(database),
                    library = DatabaseSmartLibrary(database),
                    mixEnabled = { gate.isEnabled(FamilyFeature.PARIVAAR_MIX) },
                ),
            executor = executor,
            isEnabled = { context.dataStore.data.first()[SmartDownloadsEnabledKey] ?: true },
            scheduleNext = { SmartDownloadScheduler.scheduleNext(context, it) },
        )
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 14 — the recap's snapshot store, one per process, over the app's settings DataStore.
package com.metrolist.music.saraswati.family.recap

import android.content.Context
import com.metrolist.music.utils.dataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RecapModule {
    @Provides
    @Singleton
    fun provideRecapSnapshotStore(
        @ApplicationContext context: Context,
    ): RecapSnapshotStore = RecapSnapshotStore(context.dataStore, Clock.systemDefaultZone())
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 4 — wiring for the resume store. Its stored points are read on their own scope so
// building it never blocks the thread that asked for it.
package com.metrolist.music.saraswati.craft.resume

import android.content.Context
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
object ResumeModule {
    @Provides
    @Singleton
    fun provideResumeStore(
        @ApplicationContext context: Context,
    ): ResumeStore =
        ResumeStore(
            context.dataStore,
            { System.currentTimeMillis() },
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
}

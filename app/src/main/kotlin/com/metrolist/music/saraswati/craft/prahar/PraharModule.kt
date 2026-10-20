/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 7 — Hilt wiring for the Prahar store and repository.
package com.metrolist.music.di

import android.content.Context
import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.saraswati.craft.prahar.PraharDao
import com.metrolist.music.saraswati.craft.prahar.PraharEventRow
import com.metrolist.music.saraswati.craft.prahar.PraharRepository
import com.metrolist.music.saraswati.craft.prahar.PraharStore
import com.metrolist.music.saraswati.craft.snooze.SnoozeStore
import com.metrolist.music.saraswati.family.parivaar.YouTubePlaylistPort
import com.metrolist.music.utils.dataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.LocalDateTime
import javax.inject.Singleton

private class RoomPraharDao(
    private val dao: DatabaseDao,
) : PraharDao {
    override suspend fun praharPlays(fromTimeStamp: LocalDateTime): List<PraharEventRow> = dao.praharPlays(fromTimeStamp)

    override suspend fun songsByIds(ids: List<String>): List<Song> = dao.getSongsByIds(ids)
}

@Module
@InstallIn(SingletonComponent::class)
object PraharModule {
    @Provides
    @Singleton
    fun providePraharStore(
        @ApplicationContext context: Context,
    ): PraharStore = PraharStore(context.dataStore, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @Singleton
    fun providePraharRepository(
        database: MusicDatabase,
        port: YouTubePlaylistPort,
        store: PraharStore,
        snoozeStore: SnoozeStore,
    ): PraharRepository =
        PraharRepository(
            RoomPraharDao(database),
            port,
            store,
            // SARASWATI: Phase 6 Task 2 — a snoozed song stays out of the Prahar mix.
            snoozed = { snoozeStore.awaitActiveIds() },
        )
}

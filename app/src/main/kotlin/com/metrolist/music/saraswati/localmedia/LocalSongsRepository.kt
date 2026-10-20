/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 3 — the songs on this phone, for the screen and the car. Reads come from Room only. A
// scan reads MediaStore, and only while access is GRANTED and the Local files switch is not off; otherwise
// nothing is read and the rows already held stay as they were (hidden while the switch is off). A scan that fails (the reader throws) changes nothing. The launch-time rescan
// (syncIfDue) runs at most every RESCAN_INTERVAL, and after a failure this process waits before trying again.
package com.metrolist.music.saraswati.localmedia

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.LocalFilesEnabledKey
import com.metrolist.music.constants.LocalLastScanKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.saraswati.family.parivaar.AttemptBackoff
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

class LocalAccessException(
    val access: LocalAccess,
) : IllegalStateException("Songs on this phone are not available: $access")

class LocalFilesOffException : IllegalStateException("Local files is switched off")

@Singleton
class LocalSongsRepository
    internal constructor(
        private val allSongs: Flow<List<Song>>,
        private val allCount: Flow<Int>,
        private val access: () -> LocalAccess,
        private val reader: MediaStoreAudioReader,
        private val sync: LocalLibrarySync,
        private val dataStore: DataStore<Preferences>,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
            database: MusicDatabase,
        ) : this(
            allSongs = database.localSongsByTitle(),
            allCount = database.localSongCount(),
            access = { localAccess(context) },
            reader = ContentResolverAudioReader(context.contentResolver),
            sync = LocalLibrarySync(RoomLocalSongStore(database)),
            dataStore = context.dataStore,
        )

        /** The saved switch: null when never touched. */
        val storedSwitch: Flow<Boolean?> = dataStore.data.map { it[LocalFilesEnabledKey] }.distinctUntilChanged()

        suspend fun setEnabled(enabled: Boolean) {
            dataStore.edit { it[LocalFilesEnabledKey] = enabled }
        }

        /** Whether songs on this phone may be read and served right now: access granted and the switch not off. */
        suspend fun canServe(): Boolean = LocalFiles.enabled(storedSwitch.first(), access())

        /** Whether songs on this phone are in use, now and each time that changes. */
        val serving: Flow<Boolean> =
            storedSwitch.map { LocalFiles.enabled(it, access()) }.distinctUntilChanged()

        @OptIn(ExperimentalCoroutinesApi::class)
        val songs: Flow<List<Song>> =
            storedSwitch.flatMapLatest { stored ->
                if (LocalFiles.enabled(stored, access())) allSongs else flowOf(emptyList())
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        val count: Flow<Int> =
            storedSwitch.flatMapLatest { stored ->
                if (LocalFiles.enabled(stored, access())) allCount else flowOf(0)
            }

        private val scanning = Mutex()
        private val backoff = AttemptBackoff(RETRY_GAP_MS)

        /**
         * Scans now. Fails with [LocalAccessException] or [LocalFilesOffException], without reading anything, unless
         * access is GRANTED and the Local files switch is not off.
         */
        suspend fun syncNow(): Result<LocalSyncReport> =
            scanning.withLock {
                val current = access()
                if (current != LocalAccess.GRANTED) return@withLock Result.failure(LocalAccessException(current))
                if (storedSwitch.first() == false) return@withLock Result.failure(LocalFilesOffException())
                try {
                    val rows = reader.rows()
                    // SARASWATI: fix wave P5a I3 — access or the switch may have gone while the scan ran; the rows are
                    // then not applied (a revoked grant gives a short or empty cursor, and applying it would delete).
                    val after = access()
                    if (after != LocalAccess.GRANTED) return@withLock Result.failure(LocalAccessException(after))
                    if (storedSwitch.first() == false) return@withLock Result.failure(LocalFilesOffException())
                    val report = sync.sync(rows)
                    dataStore.edit { it[LocalLastScanKey] = System.currentTimeMillis() }
                    Result.success(report)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        suspend fun syncIfDue(now: Instant) {
            if (access() != LocalAccess.GRANTED || storedSwitch.first() == false) return
            val nowMs = now.toEpochMilli()
            val last = dataStore.data.first()[LocalLastScanKey]
            if (last != null && nowMs - last in 0 until RESCAN_INTERVAL.toMillis()) return
            if (!backoff.allowed(nowMs)) return
            val result = syncNow()
            if (result.isSuccess) {
                dataStore.edit { it[LocalLastScanKey] = nowMs }
                backoff.finished()
            } else {
                backoff.failed(nowMs)
            }
        }

        companion object {
            val RESCAN_INTERVAL: Duration = Duration.ofHours(6)
            private const val RETRY_GAP_MS = 30 * 60 * 1000L
        }
    }

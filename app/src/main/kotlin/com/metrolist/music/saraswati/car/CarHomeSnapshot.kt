/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B2 — the car's Home tab never waits for YouTube.
//
// Home is built from up to four pages of YouTube's home feed plus a database
// lookup, which on a car's mobile connection can take many seconds or fail, and
// Android Auto shows a blank or spinning tab for as long as the answer is
// pending. So the tab answers at once with the last good Home (kept in memory
// and in a small file, so the first look after the phone's process restarted
// has it too) and refreshes behind the scenes: one fetch at a time, abandoned
// after [FETCH_TIMEOUT_MS], at most one per [MIN_REFRESH_GAP_MS]. When the fetch
// brings back something different, [changes] says how many rows the car should
// now expect and the service calls notifyChildrenChanged, the way the Stations
// tab does when its cache fills.
//
// With no snapshot yet (the first ever look) the answer is an empty list and
// the car asks again when the fetch lands, exactly as Stations does for a cache
// that has not loaded. A failed or empty fetch never replaces a good snapshot
// and never notifies, so the car is not sent round in circles. The file lives
// in the app's no-backup folder: it is a cache of one person's YouTube
// suggestions, not something to restore on another phone. Logging out clears it
// (App.forgetAccount -> CarHomeHolder.forget): the memory, the file and any
// refresh already under way, which is told apart by an epoch and can neither
// refill the memory nor rewrite the file after the clear.
package com.metrolist.music.saraswati.car

import android.content.Context
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** One folder row of Home, in the shape that is kept on disk. */
@Serializable
internal data class HomeRow(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val mediaType: Int,
)

internal fun MediaItem.toHomeRow(): HomeRow =
    HomeRow(
        id = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        subtitle = mediaMetadata.subtitle?.toString(),
        artwork = mediaMetadata.artworkUri?.toString(),
        mediaType = mediaMetadata.mediaType ?: MediaMetadata.MEDIA_TYPE_PLAYLIST,
    )

internal fun HomeRow.toMediaItem(): MediaItem = carBrowsableMediaItem(id, title, subtitle, artwork?.toUri(), mediaType)

/** Where the last good Home is kept between runs. Calls may block; they are made off the main thread. */
internal interface CarHomeStore {
    fun read(): List<MediaItem>?

    fun write(items: List<MediaItem>)

    fun clear()
}

internal class FileCarHomeStore(
    private val file: File,
) : CarHomeStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HomeRow.serializer())

    override fun read(): List<MediaItem>? =
        try {
            if (!file.isFile || file.length() > MAX_FILE_BYTES) {
                null
            } else {
                json
                    .decodeFromString(serializer, file.readText())
                    .filter { it.id.startsWith(HOME_ROW_PREFIX) && it.title.isNotBlank() }
                    .take(MAX_ROWS)
                    .map { it.toMediaItem() }
                    .takeIf { it.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    override fun write(items: List<MediaItem>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(serializer, items.take(MAX_ROWS).map { it.toHomeRow() }))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not replace the Home snapshot" }
        }
    }

    override fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    companion object {
        const val FILE_NAME = "car_home_snapshot.json"
        private const val MAX_FILE_BYTES = 512L * 1024
        private const val MAX_ROWS = 100

        // The rows of Home are YouTube playlists; anything else in the file is not ours.
        private const val HOME_ROW_PREFIX = "${MusicService.YOUTUBE_PLAYLIST}/"

        fun of(context: Context): FileCarHomeStore = FileCarHomeStore(File(context.noBackupFilesDir, FILE_NAME))

    }
}

internal class CarHomeSnapshot(
    private val store: CarHomeStore,
    private val fetch: suspend () -> List<MediaItem>?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val timeoutMs: Long = FETCH_TIMEOUT_MS,
    private val minRefreshGapMs: Long = MIN_REFRESH_GAP_MS,
    // A seam for the test of the epoch check: runs between the fetch returning and the commit under the lock, the one
    // window in which a clear() can come in with nothing left to cancel.
    private val afterFetch: () -> Unit = {},
) {
    @Volatile private var memory: List<MediaItem>? = null

    @Volatile private var memoryRows: List<HomeRow>? = null
    private var storeRead = false
    private var refreshJob: Job? = null
    private var lastRefreshAt: Long? = null
    private val lock = Any()

    // Bumped by clear(); a refresh that started before a clear is not allowed to keep what it fetched.
    private var epoch = 0L

    private val _changes = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** How many rows Home now has, each time a refresh brought something different. */
    val changes: Flow<Int> = _changes.asSharedFlow()

    /**
     * The Home to show now: the last good one, or nothing. Never waits for the network; the first call reads the kept
     * file on the caller's thread (the browse tree answers from a background dispatcher), and every call makes sure a
     * refresh is on its way.
     */
    fun current(): List<MediaItem> {
        val snapshot = snapshot()
        refreshInBackground()
        return snapshot ?: emptyList()
    }

    private fun snapshot(): List<MediaItem>? {
        memory?.let { return it }
        synchronized(lock) {
            if (!storeRead) {
                storeRead = true
                if (memory == null) {
                    readStored()?.let { stored ->
                        memory = stored
                        memoryRows = stored.map { it.toHomeRow() }
                    }
                }
            }
            return memory
        }
    }

    // A store that cannot be read is the same as no snapshot: Home is then empty until the refresh lands.
    private fun readStored(): List<MediaItem>? =
        try {
            store.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun refreshInBackground() {
        synchronized(lock) {
            if (refreshJob?.isActive == true) return
            val now = clock()
            lastRefreshAt?.let { if (now - it < minRefreshGapMs) return }
            lastRefreshAt = now
            val startedIn = epoch
            refreshJob = scope.launch(io) { refresh(startedIn) }
        }
    }

    /**
     * Forgets everything: the Home in memory, the kept file and the refresh in flight (cancelled, and, should it be past
     * cancelling, refused when it lands). The car is told to ask again, and the next look starts a fresh refresh.
     */
    fun clear() {
        val running: Job?
        synchronized(lock) {
            epoch++
            running = refreshJob
            refreshJob = null
            memory = null
            memoryRows = null
            storeRead = true
            lastRefreshAt = null
            try {
                store.clear()
            } catch (e: Exception) {
                // Nothing more can be done about a file that will not go; memory is already empty.
            }
        }
        running?.cancel()
        _changes.tryEmit(0)
    }

    private suspend fun refresh(startedIn: Long) {
        val fresh =
            try {
                withTimeoutOrNull(timeoutMs) { fetch() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        // Nothing, or an empty answer, never replaces a good Home: a car with a Home that worked yesterday keeps it.
        if (fresh.isNullOrEmpty()) return
        afterFetch()
        val rows = fresh.map { it.toHomeRow() }
        synchronized(lock) {
            // A sign-out (clear) since this refresh began: what it fetched may belong to the account that left.
            if (epoch != startedIn) return
            if (rows == memoryRows) return
            memory = fresh
            memoryRows = rows
            try {
                store.write(fresh)
            } catch (e: Exception) {
                // The snapshot is a convenience; the next run simply starts without it.
            }
        }
        _changes.tryEmit(fresh.size)
    }

    companion object {
        /** How long a refresh may take before it is abandoned and the snapshot stays as it was. */
        const val FETCH_TIMEOUT_MS = 8_000L

        /** A car that keeps asking does not make a request each time. */
        const val MIN_REFRESH_GAP_MS = 60_000L
    }
}

/**
 * The one live [CarHomeSnapshot] of the process, so the sign-out path (which has only a Context) can reach it the way the
 * Saral screen reaches its queue watch. The service's callback registers its snapshot when it is built and lets go of it
 * when it is released.
 */
internal object CarHomeHolder {
    @Volatile private var live: CarHomeSnapshot? = null

    fun register(snapshot: CarHomeSnapshot) {
        live = snapshot
    }

    fun unregister(snapshot: CarHomeSnapshot) {
        if (live === snapshot) live = null
    }

    /**
     * Sign-out: the live snapshot forgets its Home (and any refresh under way), and the file goes whether or not the
     * music service is running.
     */
    fun forget(store: CarHomeStore) {
        live?.clear()
        try {
            store.clear()
        } catch (e: Exception) {
            // See CarHomeSnapshot.clear.
        }
    }

    fun forget(context: Context) = forget(FileCarHomeStore.of(context))
}

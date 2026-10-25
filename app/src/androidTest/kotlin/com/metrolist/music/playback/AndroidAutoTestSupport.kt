package com.metrolist.music.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// SARASWATI: shared by the Android Auto instrumented suites. Same in-process pattern as VoiceQueryMatrixInstrumentedTest:
// instrumentation runs inside the app's own process, so ControllerPolicy's own-package rule admits these connections.
// Nothing here calls play(). Suites call idle() between calls so the owner's YouTube traffic stays human-paced.

internal const val AA_CONNECT_TIMEOUT_S = 30L
internal const val AA_CALL_TIMEOUT_S = 30L
internal const val AA_QUEUE_TIMEOUT_MS = 25_000L
internal const val AA_STABLE_MS = 3_000L
internal const val AA_SETTLE_TIMEOUT_MS = 5_000L
internal const val AA_POLL_MS = 500L
internal const val AA_IDLE_MS = 4_000L

internal data class QueueSnapshot(val count: Int, val first: String, val firstArtist: String)

internal fun idle() = Thread.sleep(AA_IDLE_MS)

// Counts consecutive empty results. True once the limit is reached: the lockout signal the owner set, so the caller stops.
internal class EmptyStreak(private val limit: Int = 3) {
    private var run = 0

    fun record(empty: Boolean): Boolean {
        run = if (empty) run + 1 else 0
        return run >= limit
    }
}

internal fun voiceItem(query: String): MediaItem =
    MediaItem.Builder()
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
        .build()

// The first track as the production candidate would look to VoiceSearchMatcher: its title and its artist.
internal fun candidateSong(snap: QueueSnapshot): Song =
    Song(
        song = SongEntity(id = "aa-first", title = snap.first, duration = -1, thumbnailUrl = null),
        artists = if (snap.firstArtist == "-") emptyList() else listOf(ArtistEntity(id = "aa-first-artist", name = snap.firstArtist)),
        album = null,
    )

internal fun queueSnapshot(c: MediaController): QueueSnapshot {
    val count = c.mediaItemCount
    val index = c.currentMediaItemIndex
    if (count == 0 || index !in 0 until count) return QueueSnapshot(count, "-", "-")
    val metadata = c.getMediaItemAt(index).mediaMetadata
    return QueueSnapshot(count, metadata.title?.toString() ?: "-", metadata.artist?.toString() ?: "-")
}

internal fun <C : Any, T> Instrumentation.onMainWith(session: C?, block: (C) -> T): T {
    val outcome = AtomicReference<Result<T>>()
    runOnMainSync {
        outcome.set(runCatching { block(checkNotNull(session) { "not connected" }) })
    }
    return outcome.get().getOrThrow()
}

internal fun <C : Any> connectAsync(instrumentation: Instrumentation, tag: String, build: () -> ListenableFuture<C>): C {
    val latch = CountDownLatch(1)
    val connected = AtomicReference<C?>(null)
    val error = AtomicReference<String?>(null)
    instrumentation.runOnMainSync {
        try {
            val future = build()
            future.addListener(
                {
                    try {
                        connected.set(future.get())
                    } catch (e: Throwable) {
                        error.set("${e.javaClass.name}: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                },
                ContextCompat.getMainExecutor(instrumentation.targetContext),
            )
        } catch (e: Throwable) {
            error.set("${e.javaClass.name}: ${e.message}")
            latch.countDown()
        }
    }
    check(latch.await(AA_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)) { "not connected within ${AA_CONNECT_TIMEOUT_S}s" }
    Log.i(tag, "connect\tresult=${error.get() ?: "connected"}")
    check(error.get() == null) { "connect failed: ${error.get()}" }
    return checkNotNull(connected.get())
}

internal class AaVoiceSession(private val instrumentation: Instrumentation, private val tag: String) {
    private var controller: MediaController? = null

    fun connect() {
        controller = connectAsync(instrumentation, tag) {
            val target = instrumentation.targetContext
            MediaController.Builder(target, SessionToken(target, ComponentName(target, MusicService::class.java)))
                .buildAsync()
        }
    }

    fun release() {
        instrumentation.runOnMainSync {
            controller?.release()
            controller = null
        }
    }

    fun <T> onMain(block: (MediaController) -> T): T = instrumentation.onMainWith(controller, block)

    fun isConnected(): Boolean = onMain { it.isConnected }

    fun snapshot(): QueueSnapshot = onMain { queueSnapshot(it) }

    // Clears any queue left by the previous query. False if the queue did not empty in time.
    fun resetQueue(): Boolean {
        onMain {
            it.stop()
            it.clearMediaItems()
        }
        val deadline = System.currentTimeMillis() + AA_SETTLE_TIMEOUT_MS
        while (snapshot().count != 0) {
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(AA_POLL_MS)
        }
        return true
    }

    fun submitVoice(query: String) {
        onMain {
            it.setMediaItems(listOf(voiceItem(query)))
            it.prepare()
        }
    }

    // Waits until the queue has more than one track and has stopped changing for AA_STABLE_MS, or gives up at the timeout.
    fun awaitQueue(): QueueSnapshot {
        val started = System.currentTimeMillis()
        var last = snapshot()
        var stableSince = started
        while (true) {
            Thread.sleep(AA_POLL_MS)
            val now = System.currentTimeMillis()
            val current = snapshot()
            if (current != last) {
                last = current
                stableSince = now
            }
            val settled = last.count > 1 && now - stableSince >= AA_STABLE_MS
            if (settled || now - started >= AA_QUEUE_TIMEOUT_MS) return last
        }
    }
}

internal class AaBrowserSession(private val instrumentation: Instrumentation, private val tag: String) {
    private var browser: MediaBrowser? = null

    fun connect() {
        browser = connectAsync(instrumentation, tag) {
            val target = instrumentation.targetContext
            MediaBrowser.Builder(target, SessionToken(target, ComponentName(target, MusicService::class.java)))
                .buildAsync()
        }
    }

    fun release() {
        instrumentation.runOnMainSync {
            browser?.release()
            browser = null
        }
    }

    fun <T> onMain(block: (MediaBrowser) -> T): T = instrumentation.onMainWith(browser, block)
}

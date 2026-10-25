package com.metrolist.music.playback

import android.content.ComponentName
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// SARASWATI: drives MusicService's real onSetMediaItems voice path through an in-process MediaController.
// The instrumentation runs inside the app's own process (its uid owns only the app package), so
// ControllerPolicy's own-package rule admits the controller. Nothing calls play(); the queue is only built.
// Rows go to logcat under VOICE_MATRIX_TAG as they are measured, so they survive an assertion failure.

private const val VOICE_MATRIX_TAG = "VOICEMATRIX"
private const val CONNECT_TIMEOUT_S = 30L
private const val QUEUE_TIMEOUT_MS = 25_000L
private const val STABLE_MS = 3_000L
private const val SETTLE_TIMEOUT_MS = 5_000L
private const val POLL_MS = 500L

private enum class Kind { PERSON, MOVIE, EXACT_TITLE }

private data class MatrixQuery(val category: String, val kind: Kind, val query: String)

private val MATRIX = listOf(
    MatrixQuery("playback singers", Kind.PERSON, "sonu nigam songs"),
    MatrixQuery("playback singers", Kind.PERSON, "arijit singh songs"),
    MatrixQuery("playback singers", Kind.PERSON, "lata mangeshkar songs"),
    MatrixQuery("playback singers", Kind.PERSON, "kishore kumar songs"),
    MatrixQuery("playback singers", Kind.PERSON, "shreya ghoshal songs"),
    MatrixQuery("music directors", Kind.PERSON, "a r rahman songs"),
    MatrixQuery("music directors", Kind.PERSON, "pritam songs"),
    MatrixQuery("actors", Kind.PERSON, "shahrukh khan songs"),
    MatrixQuery("actors", Kind.PERSON, "salman khan songs"),
    MatrixQuery("actresses", Kind.PERSON, "deepika padukone songs"),
    MatrixQuery("actresses", Kind.PERSON, "madhuri dixit songs"),
    MatrixQuery("lyricists", Kind.PERSON, "gulzar songs"),
    MatrixQuery("lyricists", Kind.PERSON, "javed akhtar songs"),
    MatrixQuery("directors", Kind.PERSON, "karan johar songs"),
    MatrixQuery("movies / albums", Kind.MOVIE, "dilwale dulhania le jayenge songs"),
    MatrixQuery("movies / albums", Kind.MOVIE, "3 idiots songs"),
    MatrixQuery("movies / albums", Kind.MOVIE, "kabhi khushi kabhie gham songs"),
    MatrixQuery("exact song titles", Kind.EXACT_TITLE, "tum hi ho"),
    MatrixQuery("exact song titles", Kind.EXACT_TITLE, "o palan hare"),
)

private data class Snapshot(val count: Int, val first: String)

private data class Row(val query: MatrixQuery, val snapshot: Snapshot, val elapsedMs: Long, val failure: String?)

@RunWith(AndroidJUnit4::class)
class VoiceQueryMatrixInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var controller: MediaController? = null

    @Test
    fun voiceQueryMatrix() {
        logIdentity()
        connect()
        val rows = mutableListOf<Row>()
        try {
            MATRIX.forEach { rows += runQuery(it) }
        } finally {
            release()
        }
        logSummary(rows)
        val failures = rows.filter { it.failure != null }
        assertTrue(
            "${failures.size} of ${rows.size} voice queries failed: " +
                failures.joinToString("; ") { "${it.query.query} -> ${it.failure}" },
            failures.isEmpty(),
        )
    }

    private fun logIdentity() {
        val target = instrumentation.targetContext
        val uid = Process.myUid()
        Log.i(
            VOICE_MATRIX_TAG,
            "identity\tmyUid=$uid\ttargetPkg=${target.packageName}\t" +
                "instrPkg=${instrumentation.context.packageName}\t" +
                "pkgsForUid=${target.packageManager.getPackagesForUid(uid)?.toList()}",
        )
    }

    private fun connect() {
        val target = instrumentation.targetContext
        val latch = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        instrumentation.runOnMainSync {
            try {
                val token = SessionToken(target, ComponentName(target, MusicService::class.java))
                val future = MediaController.Builder(target, token).buildAsync()
                future.addListener(
                    {
                        try {
                            controller = future.get()
                        } catch (e: Throwable) {
                            error.set("${e.javaClass.name}: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    },
                    ContextCompat.getMainExecutor(target),
                )
            } catch (e: Throwable) {
                error.set("${e.javaClass.name}: ${e.message}")
                latch.countDown()
            }
        }
        check(latch.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)) { "controller did not connect within ${CONNECT_TIMEOUT_S}s" }
        Log.i(VOICE_MATRIX_TAG, "connect\tresult=${error.get() ?: "connected"}")
        check(error.get() == null) { "controller connect failed: ${error.get()}" }
    }

    private fun runQuery(query: MatrixQuery): Row {
        onMain {
            it.stop()
            it.clearMediaItems()
        }
        val cleared = awaitEmptyQueue()
        onMain {
            val item = MediaItem.Builder()
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder().setSearchQuery(query.query).build(),
                )
                .build()
            it.setMediaItems(listOf(item))
            it.prepare()
        }
        val started = System.currentTimeMillis()
        val snapshot = awaitQueue()
        val elapsedMs = System.currentTimeMillis() - started
        Log.i(VOICE_MATRIX_TAG, "${query.category}\t${query.query}\tcount=${snapshot.count}\tfirst=${snapshot.first}")
        return Row(query, snapshot, elapsedMs, evaluate(query, snapshot, cleared))
    }

    private fun evaluate(query: MatrixQuery, snapshot: Snapshot, cleared: Boolean): String? {
        val minCount = when (query.kind) {
            Kind.PERSON -> 10
            Kind.MOVIE -> 5
            Kind.EXACT_TITLE -> 2
        }
        return when {
            !cleared -> "stale queue was not cleared before the query"
            snapshot.count < minCount -> "count=${snapshot.count} is below $minCount"
            query.kind == Kind.EXACT_TITLE && snapshot.first == "-" -> "no first track title"
            query.kind == Kind.EXACT_TITLE && !titleMatches(query.query, snapshot.first) ->
                "first title '${snapshot.first}' does not match the query"
            else -> null
        }
    }

    private fun titleMatches(query: String, firstTitle: String): Boolean {
        val song = Song(
            song = SongEntity(id = "matrix-first", title = firstTitle, duration = -1, thumbnailUrl = null),
            artists = emptyList(),
            album = null,
        )
        return VoiceSearchMatcher.findBest(query, listOf(song)) != null
    }

    private fun awaitEmptyQueue(): Boolean {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        while (snapshot().count != 0) {
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    private fun awaitQueue(): Snapshot {
        val started = System.currentTimeMillis()
        var last = snapshot()
        var stableSince = started
        while (true) {
            Thread.sleep(POLL_MS)
            val now = System.currentTimeMillis()
            val current = snapshot()
            if (current != last) {
                last = current
                stableSince = now
            }
            val settled = last.count > 1 && now - stableSince >= STABLE_MS
            if (settled || now - started >= QUEUE_TIMEOUT_MS) return last
        }
    }

    private fun snapshot(): Snapshot = onMain { c ->
        val count = c.mediaItemCount
        val index = c.currentMediaItemIndex
        val first = if (count > 0 && index in 0 until count) {
            c.getMediaItemAt(index).mediaMetadata.title?.toString() ?: "-"
        } else {
            "-"
        }
        Snapshot(count, first)
    }

    private fun logSummary(rows: List<Row>) {
        val failed = rows.count { it.failure != null }
        Log.i(VOICE_MATRIX_TAG, "SUMMARY\tqueries=${rows.size}\tfailed=$failed\tpassed=${rows.size - failed}")
        rows.forEach { row ->
            val status = if (row.failure == null) "PASS" else "FAIL"
            Log.i(
                VOICE_MATRIX_TAG,
                "RESULT\t$status\t${row.query.category}\t${row.query.query}\tcount=${row.snapshot.count}\t" +
                    "first=${row.snapshot.first}\tms=${row.elapsedMs}\t${row.failure ?: "ok"}",
            )
        }
    }

    private fun release() {
        instrumentation.runOnMainSync {
            controller?.release()
            controller = null
        }
    }

    private fun <T> onMain(block: (MediaController) -> T): T {
        val outcome = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync {
            outcome.set(runCatching { block(checkNotNull(controller) { "controller is not connected" }) })
        }
        return outcome.get().getOrThrow()
    }
}

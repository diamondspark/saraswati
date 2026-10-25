package com.metrolist.music.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

// SARASWATI: car browse tree through a MediaBrowser, against the same service the car uses. Every call goes to the
// real onGetLibraryRoot / onGetChildren. Nothing is played. Rows go to logcat under CARBROWSE; the assertion runs at the end.
// Calls slower than the car budget (1 s) are flagged, not failed. Stations and Family are cache-backed by design, so an
// empty answer from them does not count toward the lockout streak.

private const val BROWSE_TAG = "CARBROWSE"
private const val PAGE_ALL = 100
private const val PAGE_SIZE = 5
private const val BUDGET_MS = 1_000L

private val CACHE_BACKED = setOf(MusicService.CAR_STATIONS, MusicService.CAR_FAMILY)

private data class BrowseCall(
    val label: String,
    val parentId: String,
    val code: Int,
    val items: List<MediaItem>,
    val elapsedMs: Long,
    val error: String?,
)

@RunWith(AndroidJUnit4::class)
class CarBrowseInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val session = AaBrowserSession(instrumentation, BROWSE_TAG)
    private val calls = mutableListOf<BrowseCall>()
    private val failures = mutableListOf<String>()
    private val streak = EmptyStreak()
    private var stopped: String? = null
    private var callCount = 0

    @Test
    fun carBrowseTree() {
        session.connect()
        try {
            walkTree()
        } finally {
            session.release()
        }
        Log.i(BROWSE_TAG, "SUMMARY\tcalls=${calls.size}\tfailed=${failures.size}\tstopped=${stopped ?: "-"}")
        assertTrue(
            "${failures.size} browse checks failed: ${failures.joinToString("; ")}",
            failures.isEmpty(),
        )
    }

    private fun walkTree() {
        val rootStarted = System.currentTimeMillis()
        val root = runCatching {
            session.onMain { it.getLibraryRoot(null) }.get(AA_CALL_TIMEOUT_S, TimeUnit.SECONDS)
        }
        val rootMs = System.currentTimeMillis() - rootStarted
        root.fold(
            onSuccess = { result ->
                Log.i(BROWSE_TAG, "ROOT\tcode=${result.resultCode}\tid=${result.value?.mediaId ?: "-"}\tms=$rootMs")
                if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                    failures += "getLibraryRoot returned code ${result.resultCode}"
                }
            },
            onFailure = {
                Log.i(BROWSE_TAG, "ROOT\terror=${it.javaClass.name}: ${it.message}\tms=$rootMs")
                failures += "getLibraryRoot threw ${it.javaClass.name}: ${it.message}"
            },
        )
        if (root.isFailure) return

        val tabs = step("root children", MusicService.ROOT, 0, PAGE_ALL, cacheBacked = false)
        tabs.items.forEachIndexed { i, tab ->
            Log.i(BROWSE_TAG, "TAB\t#${i + 1}\tid=${tab.mediaId}\ttitle=${tab.mediaMetadata.title ?: "-"}")
        }
        if (tabs.items.isEmpty()) {
            failures += "root returned no tabs"
            return
        }

        val coldCounts = linkedMapOf<String, Int>()
        val tabIds = tabs.items.map { it.mediaId }
        tabIds.forEach { id ->
            if (stopped != null) return
            val cold = step("cold $id", id, 0, PAGE_ALL, cacheBacked = id in CACHE_BACKED)
            coldCounts[id] = cold.items.size
        }
        tabIds.forEach { id ->
            if (stopped != null) return
            step("cached $id", id, 0, PAGE_ALL, cacheBacked = id in CACHE_BACKED)
        }

        val biggest = coldCounts.maxByOrNull { it.value }
        if (biggest == null || biggest.value < 2 * PAGE_SIZE) {
            Log.i(BROWSE_TAG, "PAGING\tskipped\tlargest=${biggest?.let { "${it.key}:${it.value}" } ?: "-"}\tneeds>=${2 * PAGE_SIZE}")
            return
        }
        if (stopped != null) return
        val page0 = step("page 0 of ${biggest.key}", biggest.key, 0, PAGE_SIZE, cacheBacked = biggest.key in CACHE_BACKED)
        val page1 = step("page 1 of ${biggest.key}", biggest.key, 1, PAGE_SIZE, cacheBacked = biggest.key in CACHE_BACKED)
        val dupes = page0.items.map { it.mediaId }.intersect(page1.items.map { it.mediaId }.toSet())
        Log.i(BROWSE_TAG, "PAGING\t${biggest.key}\tpage0=${page0.items.size}\tpage1=${page1.items.size}\tduplicateIds=${dupes.size}")
        if (dupes.isNotEmpty()) failures += "pages of ${biggest.key} share ids ${dupes.take(5)}"
    }

    // One browse call, paced by idle() after the first. Records it, flags slow calls, and sets [stopped] on the lockout streak.
    private fun step(label: String, parentId: String, page: Int, pageSize: Int, cacheBacked: Boolean): BrowseCall {
        if (callCount++ > 0) idle()
        val started = System.currentTimeMillis()
        val outcome = runCatching {
            session.onMain { it.getChildren(parentId, page, pageSize, null) }.get(AA_CALL_TIMEOUT_S, TimeUnit.SECONDS)
        }
        val elapsed = System.currentTimeMillis() - started
        val call = outcome.fold(
            onSuccess = { BrowseCall(label, parentId, it.resultCode, it.value.orEmpty(), elapsed, null) },
            onFailure = { BrowseCall(label, parentId, -1, emptyList(), elapsed, "${it.javaClass.name}: ${it.message}") },
        )
        calls += call
        val slow = if (elapsed > BUDGET_MS) "SLOW(>${BUDGET_MS}ms)" else "ok"
        val firstThree = call.items.take(3).joinToString("|") { it.mediaMetadata.title?.toString() ?: "-" }
        Log.i(
            BROWSE_TAG,
            "CALL\t$label\tparent=$parentId\tpage=$page/$pageSize\tcode=${call.code}\tcount=${call.items.size}\t" +
                "first3=$firstThree\tms=$elapsed\t$slow\t${call.error ?: "-"}",
        )
        if (call.error != null) failures += "$label threw ${call.error}"
        else if (call.code != SessionResult.RESULT_SUCCESS) failures += "$label returned code ${call.code}"
        if (call.error == null && call.code == SessionResult.RESULT_SUCCESS && parentId !in CACHE_BACKED &&
            page == 0 && pageSize == PAGE_ALL && !label.startsWith("root")
        ) {
            if (streak.record(call.items.isEmpty()) && stopped == null) {
                stopped = "three consecutive empty results, last '$label'"
                Log.w(BROWSE_TAG, "STOP\t$stopped")
                failures += "stopped: $stopped"
            }
        }
        return call
    }
}

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

// SARASWATI: the car's search path, onSearch then onGetSearchResult through a MediaBrowser. This is a different code path
// from voice (onSetMediaItems), so it is measured on its own. Rows go to logcat under CARSEARCH; the assertion runs at the end.
// The empty query must give an empty list, not an error. A run of three consecutive empty results among the queries that
// should return songs stops the suite (lockout signal).

private const val SEARCH_TAG = "CARSEARCH"
private const val RESULT_PAGE_SIZE = 100
private const val SEARCH_BUDGET_MS = 1_000L

private val QUERIES = listOf("arijit singh", "tum hi ho", "lagaan", "", "asdkjhasdkjh")
private val EXPECT_SONGS = setOf("arijit singh", "tum hi ho", "lagaan")

@RunWith(AndroidJUnit4::class)
class CarSearchInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val session = AaBrowserSession(instrumentation, SEARCH_TAG)

    @Test
    fun carSearch() {
        session.connect()
        val failures = mutableListOf<String>()
        try {
            val streak = EmptyStreak()
            var stopped: String? = null
            QUERIES.forEach { query ->
                if (stopped != null) {
                    failures += "NOT RUN '$query': $stopped"
                    return@forEach
                }
                val outcome = runQuery(query)
                outcome.error?.let { failures += "'$query' $it" }
                if (outcome.error == null && outcome.code != SessionResult.RESULT_SUCCESS) {
                    failures += "'$query' returned code ${outcome.code}"
                }
                if (query == "" && (outcome.count != 0 || outcome.error != null)) {
                    failures += "empty query must return an empty list, got count=${outcome.count} error=${outcome.error}"
                }
                if (query in EXPECT_SONGS && streak.record(outcome.count == 0)) {
                    stopped = "three consecutive empty results, last '$query'"
                    Log.w(SEARCH_TAG, "STOP\t$stopped")
                    failures += "stopped: $stopped"
                }
            }
        } finally {
            session.release()
        }
        Log.i(SEARCH_TAG, "SUMMARY\tqueries=${QUERIES.size}\tfailed=${failures.size}")
        assertTrue(
            "${failures.size} search checks failed: ${failures.joinToString("; ")}",
            failures.isEmpty(),
        )
    }

    private class Outcome(val code: Int, val count: Int, val error: String?)

    private fun runQuery(query: String): Outcome {
        val searchStarted = System.currentTimeMillis()
        val search = runCatching {
            session.onMain { it.search(query, null) }.get(AA_CALL_TIMEOUT_S, TimeUnit.SECONDS)
        }
        val searchMs = System.currentTimeMillis() - searchStarted
        val searchError = search.exceptionOrNull()?.let { "search threw ${it.javaClass.name}: ${it.message}" }
        val searchCode = search.getOrNull()?.resultCode ?: -1

        val resultStarted = System.currentTimeMillis()
        val outcome = runCatching {
            session.onMain { it.getSearchResult(query, 0, RESULT_PAGE_SIZE, null) }.get(AA_CALL_TIMEOUT_S, TimeUnit.SECONDS)
        }
        val resultMs = System.currentTimeMillis() - resultStarted
        val items: List<MediaItem> = outcome.getOrNull()?.value.orEmpty()
        val resultError = outcome.exceptionOrNull()?.let { "getSearchResult threw ${it.javaClass.name}: ${it.message}" }
        val code = outcome.getOrNull()?.resultCode ?: -1
        val firstThree = items.take(3).joinToString("|") { it.mediaMetadata.title?.toString() ?: "-" }
        val slow = if (searchMs + resultMs > SEARCH_BUDGET_MS) "SLOW" else "ok"
        Log.i(
            SEARCH_TAG,
            "ROW\t'$query'\tsearchCode=$searchCode\tresultCode=$code\tcount=${items.size}\tfirst3=$firstThree\t" +
                "searchMs=$searchMs\tresultMs=$resultMs\t$slow\t${searchError ?: resultError ?: "-"}",
        )
        idle()
        return Outcome(code, items.size, searchError ?: resultError)
    }
}

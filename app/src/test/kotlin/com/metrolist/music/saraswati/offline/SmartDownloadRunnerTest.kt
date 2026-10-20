package com.metrolist.music.saraswati.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneOffset

class SmartDownloadRunnerTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var ledger: SmartDownloadLedger

    private val clock = Instant.parse("2026-10-19T20:00:00Z")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        ledger = SmartDownloadLedger(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun song(id: String) = Candidate(MediaMetadata(id = id, title = id, artists = emptyList(), duration = 200), CandidateSource.TOP_PLAYED)

    private class FakeExecutor(
        val outcome: (String) -> Boolean = { true },
        val hang: Boolean = false,
    ) : SmartDownloadExecutor {
        val downloaded = mutableSetOf<String>()
        val order = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val resumed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var stopPendingCalls = 0

        override suspend fun download(
            c: Candidate,
            timeoutMs: Long,
        ): Boolean {
            order += c.id
            if (hang) awaitCancellation()
            val ok = outcome(c.id)
            if (ok) downloaded += c.id
            return ok
        }

        override fun isDownloaded(id: String) = id in downloaded

        override suspend fun stop(ids: Collection<String>) {
            stopped += ids
        }

        override suspend fun resume(ids: Collection<String>) {
            resumed += ids
        }

        override suspend fun remove(ids: Collection<String>) {
            removed += ids
        }

        override suspend fun stopPending() {
            stopPendingCalls++
        }
    }

    private fun runner(
        executor: SmartDownloadExecutor,
        candidates: List<Candidate>,
        pauses: MutableList<Long> = mutableListOf(),
        scheduled: MutableList<Instant?> = mutableListOf(),
        enabled: Boolean = true,
    ) = SmartDownloadRunner(
        ledger = ledger,
        candidates = CandidateGatherer { candidates },
        executor = executor,
        isEnabled = { enabled },
        now = { clock },
        zone = { ZoneOffset.UTC },
        scheduleNext = { scheduled += it },
        pause = { pauses += it },
    )

    @Test
    fun `downloads at most 50 a night across runs`() =
        runBlocking {
            val executor = FakeExecutor()
            val runner = runner(executor, (1..120).map { song("s$it") })
            var total = 0
            var last: RunResult? = null
            repeat(4) {
                last = runner.runOnce().also { total += it.downloaded }
            }

            assertEquals(50, total)
            assertEquals(50, executor.downloaded.size)
            assertEquals(StopCause.NIGHT_FULL, last!!.stop)
            assertEquals(Instant.parse("2026-10-20T12:00:00Z"), last!!.nextNotBefore)
        }

    @Test
    fun `waits 60 s between songs`() =
        runBlocking {
            val pauses = mutableListOf<Long>()
            val result = runner(FakeExecutor(), listOf(song("a"), song("b"), song("c")), pauses).runOnce()

            assertEquals(3, result.downloaded)
            assertEquals(listOf(60_000L, 60_000L), pauses)
        }

    @Test
    fun `two failures in a row end tonight and schedule the next noon`() =
        runBlocking {
            val executor = FakeExecutor(outcome = { it == "ok" })
            val result = runner(executor, listOf(song("bad1"), song("bad2"), song("ok"))).runOnce()

            assertEquals(StopCause.FAILURES, result.stop)
            assertEquals(0, result.downloaded)
            assertEquals(listOf("bad1", "bad2"), executor.order)
            assertEquals(Instant.parse("2026-10-20T12:00:00Z"), result.nextNotBefore)
            assertEquals(listOf("bad1", "bad2"), executor.removed)
        }

    @Test
    fun `unfinished smart downloads are stopped with SMART_STOP_REASON when the budget ends`() =
        runBlocking {
            val executor = FakeExecutor(hang = true)
            val result = runner(executor, listOf(song("slow"), song("next"))).runOnce(budgetMs = 100)

            assertEquals(StopCause.BUDGET, result.stop)
            assertEquals(null, result.nextNotBefore)
            assertEquals(listOf("slow"), executor.stopped)
            assertEquals(5205, SMART_STOP_REASON)
            assertEquals(setOf("slow"), ledger.pendingIds())
        }

    @Test
    fun `a cancelled run stops what is unfinished and schedules itself`() =
        runBlocking {
            val executor = FakeExecutor(hang = true)
            val scheduled = mutableListOf<Instant?>()
            val run = scope.async { runner(executor, listOf(song("slow")), scheduled = scheduled).runOnce() }
            while (executor.order.isEmpty()) delay(10)

            run.cancel()
            run.join()

            assertEquals(listOf("slow"), executor.stopped)
            assertEquals(listOf<Instant?>(null), scheduled)
        }

    @Test
    fun `stopPending stops leftover smart downloads at process start`() =
        runBlocking {
            // With nothing unfinished in the ledger the real executor must not even build the DownloadUtil.
            DownloadManagerExecutor(ledger) { error("DownloadUtil touched") }.stopPending()

            ledger.markPending("left", clock)
            ledger.markPending("finished", clock)
            ledger.markDone("finished", clock, "2026-10-19")
            // The unfinished ids are exactly what stopPending asks the DownloadManager about.
            assertEquals(setOf("left"), ledger.pendingIds())
            var asked = false
            try {
                DownloadManagerExecutor(ledger) { asked = true; error("stop here") }.stopPending()
            } catch (e: IllegalStateException) {
                // expected: the lambda is the stand-in for the DownloadUtil
            }
            assertTrue(asked)
        }

    @Test
    fun `resumes its own stopped downloads next run`() =
        runBlocking {
            ledger.markPending("left", clock)
            val executor = FakeExecutor()
            val result = runner(executor, listOf(song("left"), song("other"))).runOnce()

            assertEquals(listOf("left"), executor.resumed)
            assertEquals(2, result.downloaded)
            assertTrue(ledger.pendingIds().isEmpty())
        }

    @Test
    fun `never stops, resumes or removes a user download`() =
        runBlocking {
            val user = "userSong"
            val executor = FakeExecutor(outcome = { it != "bad1" && it != "bad2" })
            ledger.markPending("mine", clock)
            // The user's download is in the DownloadManager but not the ledger and not among the candidates.
            runner(executor, listOf(song("mine"), song("bad1"), song("bad2"))).runOnce()

            val touched = executor.stopped + executor.resumed + executor.removed
            assertTrue(user !in touched)
            assertTrue(touched.all { it in setOf("mine", "bad1", "bad2") })
        }

    @Test
    fun `evicts past 300`() =
        runBlocking {
            (1..305).forEach { i ->
                ledger.markPending("old$i", clock)
                ledger.markDone("old$i", Instant.ofEpochSecond(1_700_000_000L + i), "2026-10-18")
            }
            val executor = FakeExecutor()
            executor.downloaded += (1..305).map { "old$it" }

            runner(executor, listOf(song("old1"))).runOnce()

            assertEquals(listOf("old2", "old3", "old4", "old5", "old6"), executor.removed)
            assertEquals(300, ledger.snapshot().entries.size)
        }

    @Test
    fun `a song the user downloaded is never evicted as a smart download`() =
        runBlocking {
            (1..305).forEach { i ->
                ledger.markPending("old$i", clock)
                ledger.markDone("old$i", Instant.ofEpochSecond(1_700_000_000L + i), "2026-10-18")
            }
            val executor = FakeExecutor()
            executor.downloaded += (1..305).map { "old$it" }
            // DownloadUtil.download() disowns old1 when the person asks for it: it is theirs now, and not a candidate.
            ledger.remove(listOf("old1"))

            runner(executor, listOf(song("other"))).runOnce()

            assertTrue("old1" !in executor.removed)
            assertTrue("old1" !in executor.stopped)
        }

    @Test
    fun `disabled does nothing`() =
        runBlocking {
            val executor = FakeExecutor()
            val result = runner(executor, listOf(song("a")), enabled = false).runOnce()

            assertEquals(StopCause.DISABLED, result.stop)
            assertTrue(executor.order.isEmpty())
        }
}

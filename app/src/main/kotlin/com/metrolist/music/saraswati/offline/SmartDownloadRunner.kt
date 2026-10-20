/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 11 — one run of "Raat ke Gaane", inside the system's job window. It downloads one song at
// a time, SPACING_MS apart, at most 50 a night, and ends the night after two failures in a row (look human: no
// bursts, no hammering a service that is refusing us). When the run ends for any reason it stops what is still
// unfinished, so nothing carries on over mobile data, and trims the ledger to 300 songs.
//
// The runner returns where the next run should start; the job service schedules it once the system has
// finished this job. If the run is cancelled (the system took the job back) the runner winds down and schedules
// by itself through [scheduleNext].
package com.metrolist.music.saraswati.offline

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

enum class StopCause { NIGHT_FULL, FAILURES, NOTHING_TO_DO, BUDGET, DISABLED, ERROR }

data class RunResult(
    val downloaded: Int,
    val stop: StopCause,
    /** When the next run may start, or null for "soon" (SmartDownloadScheduler.RESCHEDULE_DELAY_MS). */
    val nextNotBefore: Instant?,
)

class SmartDownloadRunner(
    private val ledger: SmartDownloadLedger,
    private val candidates: CandidateGatherer,
    private val executor: SmartDownloadExecutor,
    private val isEnabled: suspend () -> Boolean,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val scheduleNext: (Instant?) -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun runOnce(budgetMs: Long = RUN_BUDGET_MS): RunResult {
        var keep: Set<String>? = null
        var downloaded = 0
        var cause = StopCause.ERROR
        try {
            if (!isEnabled()) {
                cause = StopCause.DISABLED
            } else {
                val night = SmartDownloadPlan.nightKey(now(), zone())
                ledger.rollTo(night)
                reconcile(night)
                executor.resume(ledger.pendingIds())
                val gathered = candidates.gather(now())
                keep = gathered.mapTo(HashSet()) { it.id }
                cause =
                    withTimeoutOrNull(budgetMs) { download(gathered, night) { downloaded++ } }
                        ?: afterBudget(gathered, night)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                windDown(keep)
                scheduleNext(nextNotBefore(StopCause.BUDGET))
            }
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Smart downloads: run failed")
            cause = StopCause.ERROR
        }
        withContext(NonCancellable) { windDown(keep) }
        return RunResult(downloaded, cause, nextNotBefore(cause))
    }

    // A song that finished after a stop (the DownloadManager kept going) still counts for tonight.
    private suspend fun reconcile(night: String) {
        ledger.pendingIds().filter { executor.isDownloaded(it) }.forEach { ledger.markDone(it, now(), night) }
    }

    private suspend fun download(
        gathered: List<Candidate>,
        night: String,
        onDownloaded: () -> Unit,
    ): StopCause {
        val snapshot = ledger.snapshot()
        val picks = SmartDownloadPlan.pick(gathered, { executor.isDownloaded(it) }, snapshot, night)
        // Unfinished requests of ours that nothing wants any more: drop them, they would never be finished.
        val wanted = picks.mapTo(HashSet()) { it.id }
        val orphans = ledger.pendingIds().filter { it !in wanted }
        if (orphans.isNotEmpty()) {
            executor.remove(orphans)
            ledger.remove(orphans)
        }
        picks.forEachIndexed { index, c ->
            if (index > 0) pause(SPACING_MS)
            ledger.markPending(c.id, now())
            val ok = executor.download(c, SONG_TIMEOUT_MS)
            if (ok) {
                ledger.markDone(c.id, now(), night)
                onDownloaded()
            } else {
                executor.remove(listOf(c.id))
                ledger.markFailed(c.id, night)
                if (ledger.snapshot().failuresInARow >= STOP_AFTER_FAILURES) return StopCause.FAILURES
            }
        }
        return afterBudget(gathered, night)
    }

    // Whether there is more to do tonight, judged from the ledger as it now stands.
    private suspend fun afterBudget(
        gathered: List<Candidate>,
        night: String,
    ): StopCause {
        val snapshot = ledger.snapshot().rolledTo(night)
        if (snapshot.failuresInARow >= STOP_AFTER_FAILURES) return StopCause.FAILURES
        if (snapshot.doneTonight >= SmartDownloadPlan.NIGHTLY_MAX) return StopCause.NIGHT_FULL
        val more = SmartDownloadPlan.pick(gathered, { executor.isDownloaded(it) }, snapshot, night)
        return if (more.isEmpty()) StopCause.NOTHING_TO_DO else StopCause.BUDGET
    }

    private suspend fun windDown(keep: Set<String>?) {
        try {
            val unfinished = ledger.pendingIds()
            if (unfinished.isNotEmpty()) executor.stop(unfinished)
            if (keep != null) {
                val evicted = SmartDownloadPlan.evict(ledger.snapshot(), keep)
                if (evicted.isNotEmpty()) {
                    executor.remove(evicted)
                    ledger.remove(evicted)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Smart downloads: wind-down failed")
        }
    }

    private fun nextNotBefore(cause: StopCause): Instant? =
        when (cause) {
            StopCause.NIGHT_FULL, StopCause.FAILURES, StopCause.NOTHING_TO_DO, StopCause.ERROR ->
                SmartDownloadPlan.nextNightStart(now(), zone())
            StopCause.BUDGET, StopCause.DISABLED -> null
        }

    companion object {
        const val RUN_BUDGET_MS = 480_000L
        const val SPACING_MS = 60_000L
        const val SONG_TIMEOUT_MS = 180_000L
        const val STOP_AFTER_FAILURES = 2
    }
}

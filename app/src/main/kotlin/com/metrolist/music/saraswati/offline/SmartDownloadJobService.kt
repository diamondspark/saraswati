/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 11 — the system's side of "Raat ke Gaane": it starts this when the phone is on Wi-Fi,
// charging and idle. Not exported; only the system can bind it (BIND_JOB_SERVICE). [onStopJob] returns false
// because the runner schedules the next run itself.
package com.metrolist.music.saraswati.offline

import android.app.job.JobParameters
import android.app.job.JobService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmartDownloadJobService : JobService() {
    @Inject lateinit var runner: SmartDownloadRunner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running =
            scope.launch {
                val result = runner.runOnce()
                // Ended by itself: tell the system first, then schedule, so the new job is not mistaken for this one.
                jobFinished(params, false)
                if (result.stop == StopCause.DISABLED) {
                    SmartDownloadScheduler.cancel(applicationContext)
                } else {
                    SmartDownloadScheduler.scheduleNext(applicationContext, result.nextNotBefore)
                }
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

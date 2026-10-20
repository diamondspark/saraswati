/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 11 — arms the one JobScheduler job behind "Raat ke Gaane". Not persisted (a persisted
// job needs RECEIVE_BOOT_COMPLETED, which this build removes), so App.onCreate re-arms it on every process start.
// The system runs it only on an unmetered network, while charging, with the phone idle and storage not low.
package com.metrolist.music.saraswati.offline

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import java.time.Duration
import java.time.Instant

object SmartDownloadScheduler {
    const val JOB_ID = 5205
    const val RESCHEDULE_DELAY_MS = 60_000L

    /** Arms the job unless it is already waiting; with [enabled] false, cancels it instead. */
    fun ensureScheduled(
        context: Context,
        enabled: Boolean,
    ) {
        if (!enabled) {
            cancel(context)
            return
        }
        val scheduler = scheduler(context) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(jobInfo(context, RESCHEDULE_DELAY_MS))
    }

    /**
     * Replaces the job with one that may start at [notBefore] (a night's end), or [RESCHEDULE_DELAY_MS] from [now]
     * when [notBefore] is null. Call it once the running job has ended.
     */
    fun scheduleNext(
        context: Context,
        notBefore: Instant?,
        now: Instant = Instant.now(),
    ) {
        val scheduler = scheduler(context) ?: return
        val delayMs =
            if (notBefore == null) {
                RESCHEDULE_DELAY_MS
            } else {
                Duration.between(now, notBefore).toMillis().coerceAtLeast(RESCHEDULE_DELAY_MS)
            }
        scheduler.schedule(jobInfo(context, delayMs))
    }

    fun cancel(context: Context) {
        scheduler(context)?.cancel(JOB_ID)
    }

    private fun jobInfo(
        context: Context,
        latencyMs: Long,
    ): JobInfo =
        JobInfo
            .Builder(JOB_ID, ComponentName(context, SmartDownloadJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresStorageNotLow(true)
            .setPersisted(false)
            .setMinimumLatency(latencyMs)
            .build()

    private fun scheduler(context: Context): JobScheduler? = context.getSystemService(JobScheduler::class.java)
}

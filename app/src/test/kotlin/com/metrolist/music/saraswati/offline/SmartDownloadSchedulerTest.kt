package com.metrolist.music.saraswati.offline

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmartDownloadSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val jobs: JobScheduler get() = context.getSystemService(JobScheduler::class.java)

    @Test
    fun `schedules one non-persisted job requiring unmetered, charging, idle and storage not low`() {
        SmartDownloadScheduler.ensureScheduled(context, enabled = true)

        assertEquals(1, jobs.allPendingJobs.size)
        val job = jobs.getPendingJob(SmartDownloadScheduler.JOB_ID)!!
        assertEquals(5205, job.id)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, job.networkType)
        assertTrue(job.isRequireCharging)
        assertTrue(job.isRequireDeviceIdle)
        assertTrue(job.isRequireStorageNotLow)
        assertFalse(job.isPersisted)
        assertEquals(SmartDownloadJobService::class.java.name, job.service.className)
    }

    @Test
    fun `is a no-op while the job is pending`() {
        SmartDownloadScheduler.ensureScheduled(context, enabled = true)
        val first = jobs.getPendingJob(SmartDownloadScheduler.JOB_ID)

        SmartDownloadScheduler.ensureScheduled(context, enabled = true)

        assertEquals(1, jobs.allPendingJobs.size)
        assertSame(first, jobs.getPendingJob(SmartDownloadScheduler.JOB_ID))
    }

    @Test
    fun `disabled cancels the job`() {
        SmartDownloadScheduler.ensureScheduled(context, enabled = true)
        SmartDownloadScheduler.ensureScheduled(context, enabled = false)

        assertNull(jobs.getPendingJob(SmartDownloadScheduler.JOB_ID))
        SmartDownloadScheduler.ensureScheduled(context, enabled = false)
        assertTrue(jobs.allPendingJobs.isEmpty())
    }

    @Test
    fun `scheduleNext waits for the given time, at least a minute`() {
        val now = Instant.parse("2026-10-19T10:00:00Z")

        SmartDownloadScheduler.scheduleNext(context, notBefore = null, now = now)
        assertEquals(60_000L, jobs.getPendingJob(5205)!!.minLatencyMillis)

        SmartDownloadScheduler.scheduleNext(context, notBefore = now.plusSeconds(3600), now = now)
        assertEquals(3_600_000L, jobs.getPendingJob(5205)!!.minLatencyMillis)
        assertEquals(1, jobs.allPendingJobs.size)

        SmartDownloadScheduler.scheduleNext(context, notBefore = now.minusSeconds(5), now = now)
        assertEquals(60_000L, jobs.getPendingJob(5205)!!.minLatencyMillis)
    }
}

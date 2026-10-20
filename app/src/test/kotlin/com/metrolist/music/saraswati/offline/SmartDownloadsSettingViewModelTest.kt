/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 15 — tests for the Raat ke Gaane setting and its status line.
package com.metrolist.music.saraswati.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.SmartDownloadLedgerKey
import com.metrolist.music.constants.SmartDownloadsEnabledKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SmartDownloadsSettingViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var ledger: SmartDownloadLedger
    private val armed = CopyOnWriteArrayList<Boolean>()
    private val executor = RecordingExecutor()

    private val labels =
        object : SmartDownloadStatusLabels {
            override fun lastNight(count: Int) = "last:$count"
            override val notRunYet = "never"
            override val off = "off"
        }

    // 08:00 on 20 Oct (UTC) belongs to the night keyed 19 Oct.
    private val now = Instant.parse("2026-10-20T08:00:00Z")

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

    private fun viewModel() =
        SmartDownloadsSettingViewModel(
            preferences = dataStore,
            ledger = ledger,
            executor = executor,
            arm = { armed += it },
            labels = labels,
            clock = { now },
            zone = ZoneOffset.UTC,
            scope = scope,
        )

    private fun eventually(check: () -> Boolean) =
        runBlocking {
            withTimeout(5_000) {
                while (!check()) delay(10)
            }
        }

    private fun seedLedger(snapshot: LedgerSnapshot) =
        runBlocking {
            dataStore.edit { it[SmartDownloadLedgerKey] = Json.encodeToString(LedgerSnapshot.serializer(), snapshot) }
        }

    @Test
    fun `turning off cancels the job and stops pending smart downloads`() {
        val vm = viewModel()

        vm.setEnabled(false)

        eventually { armed == listOf(false) && executor.stopPendingCalls.get() == 1 }
        assertEquals(false, runBlocking { dataStore.data.first()[SmartDownloadsEnabledKey] })
        assertEquals("off", runBlocking { withTimeout(5_000) { vm.status.first { it == "off" } } })
    }

    @Test
    fun `turning on schedules the job`() {
        val vm = viewModel()

        vm.setEnabled(true)

        eventually { armed == listOf(true) }
        assertEquals(0, executor.stopPendingCalls.get())
        assertTrue(runBlocking { dataStore.data.first()[SmartDownloadsEnabledKey] } ?: false)
    }

    @Test
    fun `status shows last night's count`() {
        seedLedger(LedgerSnapshot(night = "2026-10-19", doneTonight = 37, lastNightCount = 4))

        val status = runBlocking { withTimeout(5_000) { viewModel().status.first { it != "never" } } }

        assertEquals("last:37", status)
    }

    @Test
    fun `status shows never-run before the first night`() {
        val status = runBlocking { withTimeout(5_000) { viewModel().status.first { it == "never" } } }

        assertEquals("never", status)
    }

    @Test
    fun `status shows zero when the last run is older than yesterday`() {
        seedLedger(LedgerSnapshot(night = "2026-10-15", doneTonight = 30))

        val status = runBlocking { withTimeout(5_000) { viewModel().status.first { it != "never" } } }

        assertEquals("last:0", status)
    }

    private class RecordingExecutor : SmartDownloadExecutor {
        val stopPendingCalls = AtomicInteger(0)

        override suspend fun download(
            c: Candidate,
            timeoutMs: Long,
        ): Boolean = error("not used")

        override fun isDownloaded(id: String): Boolean = false

        override suspend fun stop(ids: Collection<String>) = Unit

        override suspend fun resume(ids: Collection<String>) = Unit

        override suspend fun remove(ids: Collection<String>) = Unit

        override suspend fun stopPending() {
            stopPendingCalls.incrementAndGet()
        }
    }
}

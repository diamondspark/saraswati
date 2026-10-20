package com.metrolist.music.saraswati.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.SmartDownloadLedgerKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class SmartDownloadLedgerTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val t1 = Instant.ofEpochMilli(1_700_000_000_000L)
    private val t2 = Instant.ofEpochMilli(1_700_000_100_000L)

    @Test
    fun `round-trips`() =
        runBlocking {
            val ledger = SmartDownloadLedger(dataStore)
            ledger.markPending("a", t1)
            ledger.markDone("a", t2, "2026-10-18")
            ledger.markPending("b", t1)
            ledger.markFailed("b", "2026-10-18")
            ledger.markDone("c", t2, "2026-10-19")

            val read = SmartDownloadLedger(dataStore).snapshot()

            assertEquals(LedgerEntry(downloadedAt = t2.toEpochMilli()), read.entries["a"])
            assertEquals(LedgerEntry(failures = 1), read.entries["b"])
            assertEquals("2026-10-19", read.night)
            assertEquals(1, read.doneTonight)
            assertEquals(0, read.failuresInARow)
            assertEquals(1, read.lastNightCount)

            ledger.remove(listOf("a", "c"))
            assertEquals(setOf("b"), ledger.snapshot().entries.keys)
        }

    @Test
    fun `corrupt data reads as empty`() =
        runBlocking {
            dataStore.edit { it[SmartDownloadLedgerKey] = "{not json" }
            val ledger = SmartDownloadLedger(dataStore)

            assertEquals(LedgerSnapshot.EMPTY, ledger.snapshot())
            assertEquals(emptySet<String>(), ledger.pendingIds())

            ledger.markPending("a", t1)
            assertEquals(setOf("a"), ledger.pendingIds())
        }

    @Test
    fun `pendingIds lists only unfinished entries`() =
        runBlocking {
            val ledger = SmartDownloadLedger(dataStore)
            ledger.markPending("waiting", t1)
            ledger.markPending("done", t1)
            ledger.markDone("done", t2, "2026-10-19")
            ledger.markPending("failed", t1)
            ledger.markFailed("failed", "2026-10-19")

            assertEquals(setOf("waiting"), ledger.pendingIds())
        }

    @Test
    fun `failures in a row count up and a success clears them`() =
        runBlocking {
            val ledger = SmartDownloadLedger(dataStore)
            ledger.markFailed("a", "2026-10-19")
            ledger.markFailed("b", "2026-10-19")
            assertEquals(2, ledger.snapshot().failuresInARow)

            ledger.markDone("c", t1, "2026-10-19")
            assertEquals(0, ledger.snapshot().failuresInARow)

            ledger.markFailed("d", "2026-10-20")
            val next = ledger.snapshot()
            assertEquals(1, next.failuresInARow)
            assertEquals(1, next.lastNightCount)
        }
}

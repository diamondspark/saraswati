/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — the daily mix refresh against a fake YouTube account, a fake hub and a real
// file-backed DataStore.
package com.metrolist.music.saraswati.family.parivaar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.saraswati.family.recap.RecapSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

class ParivaarMixRefresherTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: ParivaarMixStore
    private val port = FakePort()
    private val hub = FakeHub()
    private val identity = FakeIdentity()
    private val t0 = Instant.parse("2026-10-19T08:00:00Z")

    private var unmetered = true
    private val downloaded = mutableSetOf<String>()
    private val enqueued = mutableListOf<String>()
    private val downloader =
        MixDownloader(
            isUnmetered = { unmetered },
            isDownloadedOrQueued = { it in downloaded },
            enqueue = { enqueued += it.videoId },
        )

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        store = ParivaarMixStore(dataStore, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun refresher(enabled: Boolean = true) = ParivaarMixRefresher(hub, port, identity, store, downloader, enabled = { enabled })

    /** Registers a member whose playlist holds [ids]; the id of the playlist is "PL<device>". */
    private fun member(
        deviceId: String,
        name: String,
        vararg ids: String,
    ) {
        val playlistId = "PL$deviceId"
        port.seed(playlistId, *ids)
        hub.rows = hub.rows + hub.row(deviceId, name, playlistId)
    }

    private fun held() = runBlocking { store.mix.first() }

    @Test
    fun `builds from every registered member including this phone`() =
        runBlocking {
            member("dev-me", "Mohit", "a1", "a2", "shared")
            member("dev-mummy", "Mummy", "m1", "shared", "m2")
            member("dev-papa", "Papa", "p1", "p2")

            val result = refresher().refreshIfDue(t0)

            assertTrue(result.isSuccess)
            val ids = held().tracks.map { it.videoId }
            assertEquals("shared", ids.first())
            assertEquals(setOf("a1", "a2", "shared", "m1", "m2", "p1", "p2"), ids.toSet())
            assertEquals(t0.toEpochMilli(), held().builtAt)
        }

    @Test
    fun `a member whose playlist fails to load is skipped`() =
        runBlocking {
            member("dev-me", "Mohit", "a1", "shared")
            member("dev-mummy", "Mummy", "m1", "shared")
            member("dev-papa", "Papa", "p1")
            port.failEntries["PLdev-papa"] = IOException("offline")

            val result = refresher().refreshIfDue(t0)

            assertTrue(result.isSuccess)
            assertEquals(setOf("a1", "m1", "shared"), held().tracks.map { it.videoId }.toSet())
        }

    @Test
    fun `the members read are handed to the family recap`() =
        runBlocking {
            val recap = RecapSnapshotStore(dataStore, Clock.systemUTC())
            member("dev-me", "Mohit", "a1", "shared")
            member("dev-mummy", "Mummy", "m1", "shared")

            ParivaarMixRefresher(hub, port, identity, store, downloader, recap = recap).refreshIfDue(t0)

            assertEquals(setOf("Mohit", "Mummy"), recap.current()!!.members.map { it.memberName }.toSet())
        }

    @Test
    fun `radio is requested only with an overlap seed`() =
        runBlocking {
            port.radioTracks = listOf(track("r1"), track("r2"))
            member("dev-me", "Mohit", "a1", "a2")
            member("dev-mummy", "Mummy", "m1", "m2")

            refresher().refreshIfDue(t0)

            assertEquals(emptyList<String>(), port.radioSeeds)
            assertTrue(held().tracks.none { it.videoId.startsWith("r") })

            member("dev-papa", "Papa", "a1")
            val later = refresher().refreshIfDue(t0.plus(Duration.ofDays(2)))

            assertTrue(later.isSuccess)
            assertEquals(listOf("a1"), port.radioSeeds)
            assertTrue(held().tracks.map { it.videoId }.containsAll(listOf("r1", "r2")))
        }

    @Test
    fun `saves the mix and downloads only on unmetered networks`() =
        runBlocking {
            member("dev-me", "Mohit", "a1", "shared")
            member("dev-mummy", "Mummy", "m1", "shared")
            downloaded += "a1"
            unmetered = false

            refresher().refreshIfDue(t0)

            assertEquals(3, held().tracks.size)
            assertEquals(emptyList<String>(), enqueued)

            unmetered = true
            refresher().refreshIfDue(t0.plus(Duration.ofDays(2)))

            assertEquals(setOf("m1", "shared"), enqueued.toSet())
            assertEquals(2, enqueued.size)
        }

    @Test
    fun `does nothing when parivaar_mix is off`() =
        runBlocking {
            member("dev-me", "Mohit", "a1", "shared")
            member("dev-mummy", "Mummy", "m1", "shared")

            val result = refresher(enabled = false).refreshIfDue(t0)

            assertTrue(result.isSuccess)
            assertEquals(0, hub.listCalls)
            assertEquals(0, held().tracks.size)
            assertEquals(emptyList<String>(), enqueued)
        }

    @Test
    fun `does nothing within 24 hours of the last build`() =
        runBlocking {
            member("dev-me", "Mohit", "a1", "shared")
            member("dev-mummy", "Mummy", "m1", "shared")
            refresher().refreshIfDue(t0)
            val calls = hub.listCalls
            member("dev-papa", "Papa", "p1", "shared")

            val early = refresher().refreshIfDue(t0.plus(Duration.ofHours(24)).minusSeconds(1))
            val due = refresher().refreshIfDue(t0.plus(Duration.ofHours(24)))

            assertTrue(early.isSuccess)
            assertEquals(calls + 1, hub.listCalls)
            assertTrue(due.isSuccess)
            assertTrue(held().tracks.any { it.videoId == "p1" })
        }

    @Test
    fun `an empty build leaves the mix already held and tries again next launch`() =
        runBlocking {
            member("dev-me", "Mohit", "a1", "shared")
            member("dev-mummy", "Mummy", "m1", "shared")
            refresher().refreshIfDue(t0)
            val before = held()
            port.failEntries["PLdev-mummy"] = IOException("offline")

            val next = refresher().refreshIfDue(t0.plus(Duration.ofDays(2)))

            assertTrue(next.isSuccess)
            assertEquals(before, held())

            port.failEntries.clear()
            refresher().refreshIfDue(t0.plus(Duration.ofDays(2)).plusSeconds(5))
            assertEquals(t0.plus(Duration.ofDays(2)).plusSeconds(5).toEpochMilli(), held().builtAt)
        }

    @Test
    fun `a hub failure is a failure and keeps the mix`() =
        runBlocking {
            member("dev-me", "Mohit", "a1", "shared")
            member("dev-mummy", "Mummy", "m1", "shared")
            refresher().refreshIfDue(t0)
            val before = held()
            hub.failList = IOException("hub down")

            val result = refresher().refreshIfDue(t0.plus(Duration.ofDays(2)))

            assertTrue(result.isFailure)
            assertEquals(before, held())
        }

    @Test
    fun `without a session nothing is asked of the hub`() =
        runBlocking {
            identity.token = null

            assertTrue(refresher().refreshIfDue(t0).isSuccess)
            assertEquals(0, hub.listCalls)
        }
}

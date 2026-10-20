/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 14 — the recap's snapshots against a real file-backed DataStore, with a clock the test moves.
package com.metrolist.music.saraswati.family.recap

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.music.saraswati.family.parivaar.MemberTopSongs
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RecapSnapshotStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private val day = 24L * 60 * 60 * 1000
    private val clock = MovingClock(Instant.parse("2026-10-01T08:00:00Z"))

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store() = RecapSnapshotStore(dataStore, clock)

    private fun members(vararg ids: String) =
        listOf(
            MemberTopSongs("me", "Mohit", ids.map { MixTrack(it, it, "A", null, null) }),
            MemberTopSongs("mum", "Mummy", listOf(MixTrack("shared", "shared", "A", null, null))),
        )

    @Test
    fun `daily refreshes keep the week to compare against for ever`() =
        runBlocking {
            val store = store()
            repeat(40) {
                store.record(members("s$it"))
                clock.advance(day)
            }
            clock.advance(-day)

            // Steady state: the previous snapshot is the newest one at least 7 days old, never the install-time one.
            val previous = store.previous()!!
            val age = store.current()!!.takenAt - previous.takenAt
            assertTrue("age was $age", age in 7 * day..8 * day)
            assertTrue(store.snapshots.value.size <= 10)
            val reloaded = store()
            reloaded.ensureLoaded()
            assertEquals(previous.takenAt, reloaded.previous()!!.takenAt)
            assertEquals(store.current()!!.takenAt, reloaded.current()!!.takenAt)
        }

    @Test
    fun `new this week needs a snapshot at least 7 days older`() =
        runBlocking {
            val store = store()
            store.record(members("first"))
            clock.advance(6 * day)
            store.record(members("second"))

            assertNull(store.previous())

            clock.advance(day)
            store.record(members("third"))

            assertEquals(clock.instant().minusMillis(7 * day).toEpochMilli(), store.previous()!!.takenAt)
            assertEquals(clock.instant().toEpochMilli(), store.current()!!.takenAt)
        }

    @Test
    fun `local songs are not kept`() =
        runBlocking {
            val store = store()
            store.record(listOf(MemberTopSongs("me", "Mohit", listOf(MixTrack("local:42", "Mine", "A", null, null), MixTrack("yt1", "Yt", "A", null, null)))))

            assertEquals(listOf("yt1"), store.current()!!.members.single().tracks.map { it.videoId })
        }

    @Test
    fun `snapshots survive a restart`() =
        runBlocking {
            store().record(members("kept"))

            val reloaded = store()
            reloaded.ensureLoaded()

            assertEquals(listOf("kept"), reloaded.current()!!.members.first().tracks.map { it.videoId })
        }

    @Test
    fun `no snapshots gives no current or previous`() {
        val store = store()

        assertNull(store.current())
        assertNull(store.previous())
    }

    private class MovingClock(
        private var now: Instant,
    ) : Clock() {
        fun advance(millis: Long) {
            now = now.plusMillis(millis)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }
}

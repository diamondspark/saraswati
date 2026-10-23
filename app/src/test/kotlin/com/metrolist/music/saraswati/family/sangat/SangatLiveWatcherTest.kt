/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — SangatLiveWatcher on virtual time: nothing sleeps, and the poll count is exact.
package com.metrolist.music.saraswati.family.sangat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubException
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.LiveSangatRoom
import com.metrolist.music.saraswati.family.VirtualTimeDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SangatLiveWatcherTest {
    @get:Rule val folder = TemporaryFolder()

    private class ScriptedSangatHub : SangatHubApi {
        val polls = mutableListOf<String>()
        var rooms: Result<List<LiveSangatRoom>> = Result.success(emptyList())
        var throwing: Throwable? = null

        override suspend fun getLiveSangat(sessionToken: String): Result<List<LiveSangatRoom>> {
            polls += sessionToken
            throwing?.let { throw it }
            return rooms
        }

        override suspend fun setSangatCarMode(
            sessionToken: String,
            roomCode: String,
            carMode: Boolean,
        ): Result<Unit> = Result.success(Unit)
    }

    private lateinit var time: VirtualTimeDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var ioScope: CoroutineScope
    private lateinit var killSwitchHub: FakeHub
    private lateinit var gate: FamilyFeatureGate
    private val hub = ScriptedSangatHub()
    private var token: String? = "sess"

    private val identity =
        object : HubIdentity {
            override suspend fun sessionToken() = token

            override suspend fun deviceId() = if (token == null) null else "me"
        }

    @Before
    fun setUp() {
        time = VirtualTimeDispatcher()
        scope = CoroutineScope(SupervisorJob() + time)
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        killSwitchHub = FakeHub()
        val dataStore = PreferenceDataStoreFactory.create(scope = ioScope) { folder.newFile("settings.preferences_pb") }
        gate = FamilyFeatureGate(killSwitchHub, identity, dataStore, ioScope)
        runBlocking { withTimeout(5_000) { gate.hydration.join() } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        ioScope.cancel()
    }

    private fun watcher() = SangatLiveWatcher(hub, identity, gate) { time.currentTime }

    private fun room(
        code: String,
        host: String = "other",
        car: Boolean = false,
    ) = LiveSangatRoom(code, "Asha", host, car, 1, "2026-10-19T10:00:00.000Z")

    @Test
    fun `polls every 30 seconds only between start and stop`() {
        val watcher = watcher()
        time.runCurrent()
        assertEquals(0, hub.polls.size)

        watcher.start(scope)
        time.runCurrent()
        assertEquals(1, hub.polls.size)

        time.advanceBy(29_999)
        assertEquals(1, hub.polls.size)
        time.advanceBy(1)
        assertEquals(2, hub.polls.size)
        time.advanceBy(60_000)
        assertEquals(4, hub.polls.size)

        watcher.stop()
        time.advanceBy(300_000)
        assertEquals(4, hub.polls.size)
        assertEquals(listOf("sess"), hub.polls.distinct())
        assertEquals(30_000L, SangatLiveWatcher.POLL_INTERVAL_MS)
    }

    @Test
    fun `excludes rooms this device hosts`() {
        hub.rooms = Result.success(listOf(room("AAAA", host = "me"), room("BBBB", host = "other")))
        val watcher = watcher()

        watcher.start(scope)
        time.runCurrent()

        assertEquals(listOf("BBBB"), watcher.liveRooms.value.map { it.roomCode })
    }

    @Test
    fun `emits one car prompt per room code`() {
        val got = mutableListOf<String>()
        val watcher = watcher()
        scope.launch { watcher.carPrompts.collect { got += it.roomCode } }
        time.runCurrent()
        hub.rooms = Result.success(listOf(room("BBBB", car = true), room("CCCC", car = false), room("DDDD", host = "me", car = true)))

        watcher.start(scope)
        time.runCurrent()
        time.advanceBy(30_000)
        time.advanceBy(30_000)

        assertEquals(listOf("BBBB"), got)
        assertEquals(3, hub.polls.size)

        // CCCC starts driving: its first car-mode poll prompts; BBBB is not repeated.
        hub.rooms = Result.success(listOf(room("BBBB", car = true), room("CCCC", car = true)))
        time.advanceBy(30_000)
        assertEquals(listOf("BBBB", "CCCC"), got)
    }

    @Test
    fun `a hub failure yields an empty list and no exception`() {
        val watcher = watcher()
        hub.rooms = Result.success(listOf(room("BBBB")))
        watcher.start(scope)
        time.runCurrent()
        assertEquals(1, watcher.liveRooms.value.size)

        hub.rooms = Result.failure(HubException(503, "off"))
        time.advanceBy(30_000)
        assertTrue(watcher.liveRooms.value.isEmpty())

        hub.rooms = Result.success(listOf(room("BBBB")))
        hub.throwing = java.io.IOException("boom")
        time.advanceBy(30_000)
        assertTrue(watcher.liveRooms.value.isEmpty())

        hub.throwing = null
        time.advanceBy(30_000)
        assertEquals(1, watcher.liveRooms.value.size)
    }

    @Test
    fun `does not poll while sangat is disabled`() {
        killSwitchHub.killSwitches = mapOf("sangat" to false)
        runBlocking { gate.refresh() }
        hub.rooms = Result.success(listOf(room("BBBB", car = true)))
        val watcher = watcher()

        watcher.start(scope)
        time.runCurrent()
        time.advanceBy(120_000)

        assertEquals(0, hub.polls.size)
        assertTrue(watcher.liveRooms.value.isEmpty())
    }

    @Test
    fun `does not poll without a session`() {
        token = null
        val watcher = watcher()

        watcher.start(scope)
        time.runCurrent()
        time.advanceBy(60_000)

        assertEquals(0, hub.polls.size)
    }

    private fun awaitGate(
        feature: com.metrolist.music.saraswati.family.FamilyFeature,
        enabled: Boolean,
    ) {
        repeat(100) {
            time.runCurrent()
            if (gate.isEnabled(feature) == enabled) return
            Thread.sleep(50)
        }
        assertEquals(enabled, gate.isEnabled(feature))
    }

    @Test
    fun `a gate that says off recovers within about five minutes while the watcher runs`() {
        killSwitchHub.killSwitches = mapOf("sangat" to false)
        runBlocking { gate.refresh() }
        killSwitchHub.killSwitches = mapOf("sangat" to true)
        val watcher = watcher()

        watcher.start(scope)
        time.runCurrent()
        time.advanceBy(SangatLiveWatcher.POLL_INTERVAL_MS * (SangatLiveWatcher.GATE_REFRESH_EVERY_POLLS - 2))
        assertEquals("not asked yet", 1, killSwitchHub.callsTo("getKillSwitches").size)

        time.advanceBy(SangatLiveWatcher.POLL_INTERVAL_MS * 2)
        awaitGate(com.metrolist.music.saraswati.family.FamilyFeature.SANGAT, true)

        hub.rooms = Result.success(listOf(room("BBBB")))
        time.advanceBy(SangatLiveWatcher.POLL_INTERVAL_MS)
        assertTrue("polling resumed", hub.polls.isNotEmpty())
        assertEquals(1, watcher.liveRooms.value.size)
    }

    @Test
    fun `a switch turned off while the app is open stops the polling at the next refresh`() {
        val watcher = watcher()
        watcher.start(scope)
        time.runCurrent()
        killSwitchHub.killSwitches = mapOf("sangat" to false)

        time.advanceBy(SangatLiveWatcher.POLL_INTERVAL_MS * SangatLiveWatcher.GATE_REFRESH_EVERY_POLLS)
        awaitGate(com.metrolist.music.saraswati.family.FamilyFeature.SANGAT, false)
        val before = hub.polls.size
        time.advanceBy(SangatLiveWatcher.POLL_INTERVAL_MS * 3)

        assertEquals(before, hub.polls.size)
        assertTrue(watcher.liveRooms.value.isEmpty())
    }

    @Test
    fun `a 503 from the hub refreshes the gate at once`() {
        hub.rooms = Result.failure(HubException(503, "off"))
        killSwitchHub.killSwitches = mapOf("sangat" to false)
        val watcher = watcher()

        watcher.start(scope)
        time.runCurrent()

        assertEquals(1, killSwitchHub.callsTo("getKillSwitches").size)
        awaitGate(com.metrolist.music.saraswati.family.FamilyFeature.SANGAT, false)
    }

    // SARASWATI: regression test for an "Already resumed" flake. The 503-triggered refresh (pollOnce) and
    // the periodic "every GATE_REFRESH_EVERY_POLLS polls" refresh both call gate.refresh() around the same
    // virtual-time instant; killSwitchesGate pins that overlap down instead of leaving it to real-thread
    // timing luck, by holding the first refresh's hub call open (inside refreshLock) until the second has
    // queued up behind it. Releasing the gate then lets a cascade of real gate.refresh() calls each do a
    // real dataStore.edit() write on Dispatchers.IO while virtual time keeps advancing on the test thread —
    // the exact two-clock overlap that corrupted VirtualTimeDispatcher's unsynchronized queue (reproduced
    // locally as a NullPointerException out of java.util.PriorityQueue's comparator, and on CI as
    // kotlinx.coroutines' "Already resumed" from a Task run twice off the back of that corruption).
    @Test
    fun `overlapping 503 and periodic gate refreshes do not corrupt virtual time`() {
        hub.rooms = Result.failure(HubException(503, "off"))
        killSwitchHub.killSwitchesGate = CompletableDeferred()
        val watcher = watcher()

        watcher.start(scope)
        time.runCurrent() // poll #1: 503 -> refreshGateAsync -> gate.refresh() blocks in getKillSwitches,
        // holding refreshLock.

        // Poll #2..#10 each see the same 503 and queue up their own refreshGateAsync behind refreshLock;
        // poll #10 also fires the periodic refresh, so both triggers are now pending at once.
        time.advanceBy(SangatLiveWatcher.POLL_INTERVAL_MS * (SangatLiveWatcher.GATE_REFRESH_EVERY_POLLS - 1))

        killSwitchHub.killSwitchesGate?.complete(Unit)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            time.advanceBy(SangatLiveWatcher.POLL_INTERVAL_MS)
        }

        assertTrue(watcher.liveRooms.value.isEmpty())
    }
}

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `the live list is fresh for ten seconds after a poll and no longer`() {
        val watcher = watcher()
        assertFalse(watcher.listIsFresh())

        watcher.start(scope)
        time.runCurrent()
        assertTrue(watcher.listIsFresh())

        time.advanceBy(SangatLiveWatcher.LIST_FRESH_MS)
        assertTrue(watcher.listIsFresh())
        time.advanceBy(1)
        assertFalse(watcher.listIsFresh())
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
    fun `emits one prompt per room code, car or not`() {
        val got = mutableListOf<String>()
        val watcher = watcher()
        scope.launch { watcher.carPrompts.collect { got += it.roomCode } }
        time.runCurrent()
        hub.rooms = Result.success(listOf(room("BBBB", car = true), room("CCCC", car = false), room("DDDD", host = "me", car = true)))

        watcher.start(scope)
        time.runCurrent()
        time.advanceBy(30_000)
        time.advanceBy(30_000)

        assertEquals(listOf("BBBB", "CCCC"), got)
        assertEquals(3, hub.polls.size)

        // CCCC starts driving: it was already prompted, so it is not prompted again; BBBB is not repeated.
        hub.rooms = Result.success(listOf(room("BBBB", car = true), room("CCCC", car = true)))
        time.advanceBy(30_000)
        assertEquals(listOf("BBBB", "CCCC"), got)
    }

    @Test
    fun `a non-car room prompts exactly once while it stays live`() {
        val got = mutableListOf<String>()
        val watcher = watcher()
        scope.launch { watcher.carPrompts.collect { got += it.roomCode } }
        time.runCurrent()
        hub.rooms = Result.success(listOf(room("EEEE", car = false)))

        watcher.start(scope)
        time.runCurrent()
        repeat(5) { time.advanceBy(30_000) }

        assertEquals(6, hub.polls.size)
        assertEquals(listOf("EEEE"), got)
    }

    @Test
    fun `a fresh lookup returns the hub's live list, and nothing without a session`() {
        hub.rooms = Result.success(listOf(room("CCCC", car = true)))
        val watcher = watcher()

        assertEquals(listOf("CCCC"), runBlocking { watcher.fetchLiveRooms() }?.map { it.roomCode })

        token = null
        assertNull(runBlocking { watcher.fetchLiveRooms() })
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
    fun `answeredLive is null before the first answer and set after a successful poll`() {
        val watcher = watcher()
        assertNull(watcher.answeredLive.value)
        hub.rooms = Result.success(listOf(room("BBBB"), room("MINE", host = "me")))
        watcher.start(scope)
        time.runCurrent()
        assertEquals(listOf("BBBB"), watcher.answeredLive.value?.map { it.roomCode })
    }

    @Test
    fun `answeredLive keeps its last answer through a failed call, a thrown exception and a missing token`() {
        val watcher = watcher()
        hub.rooms = Result.success(listOf(room("BBBB")))
        watcher.start(scope)
        time.runCurrent()

        hub.rooms = Result.failure(HubException(500, "down"))
        time.advanceBy(30_000)
        assertTrue(watcher.liveRooms.value.isEmpty())
        assertEquals(listOf("BBBB"), watcher.answeredLive.value?.map { it.roomCode })

        hub.rooms = Result.success(listOf(room("BBBB")))
        hub.throwing = java.io.IOException("boom")
        time.advanceBy(30_000)
        assertEquals(listOf("BBBB"), watcher.answeredLive.value?.map { it.roomCode })

        hub.throwing = null
        token = null
        time.advanceBy(30_000)
        assertEquals(listOf("BBBB"), watcher.answeredLive.value?.map { it.roomCode })
    }

    @Test
    fun `answeredLive is null again after stop`() {
        val watcher = watcher()
        hub.rooms = Result.success(listOf(room("BBBB")))
        watcher.start(scope)
        time.runCurrent()
        assertEquals(1, watcher.answeredLive.value?.size)
        watcher.stop()
        assertNull(watcher.answeredLive.value)
    }

    @Test
    fun `answeredLive is an empty list when the sangat gate is off`() {
        val watcher = watcher()
        watcher.start(scope)
        time.runCurrent()
        killSwitchHub.killSwitches = mapOf("sangat" to false)
        runBlocking { gate.refresh() }
        hub.rooms = Result.success(listOf(room("BBBB")))
        time.advanceBy(30_000)
        assertEquals(emptyList<LiveSangatRoom>(), watcher.answeredLive.value)
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

    // SARASWATI: integration-level companion to VirtualTimeDispatcherTest (the real, reliable regression
    // coverage for the "Already resumed" flake — that unit test drives the dispatcher's own entry points
    // from many real threads directly; this one only checks that the app-level overlap it was modeled on
    // doesn't blow up end to end). The 503-triggered refresh (pollOnce) and the periodic "every
    // GATE_REFRESH_EVERY_POLLS polls" refresh both call gate.refresh() around the same virtual-time instant;
    // killSwitchesGate pins that overlap down instead of leaving it to real-thread timing luck, by holding
    // the first refresh's hub call open (inside refreshLock) until the second has queued up behind it.
    // Releasing the gate then lets a cascade of real gate.refresh() calls each do a real dataStore.edit()
    // write on Dispatchers.IO while virtual time keeps advancing on the test thread — the same two-clock
    // overlap VirtualTimeDispatcherTest targets directly, bounded here by poll count rather than wall clock
    // so this test's own runtime doesn't depend on the machine running it.
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
        // Enough further polls (each a real gate.refresh() -> dataStore.edit() round trip on Dispatchers.IO)
        // to give the cross-thread handoff many chances to land while virtual time keeps moving here.
        repeat(500) {
            time.advanceBy(SangatLiveWatcher.POLL_INTERVAL_MS)
        }

        assertTrue(
            "expected many real gate refreshes to have actually landed, not just queued",
            killSwitchHub.callsTo("getKillSwitches").size >= SangatLiveWatcher.GATE_REFRESH_EVERY_POLLS,
        )
        assertTrue(watcher.liveRooms.value.isEmpty())
    }
}

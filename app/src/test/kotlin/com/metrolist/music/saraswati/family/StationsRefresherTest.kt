/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — every paired phone keeps its station cache
// fresh on launch and resume, not only the ones that show Saral: an adult,
// non-owner phone has no other way to fill the cache the car's Stations tab
// reads. Quiet by design: no session means no call, and no failure ever
// empties or breaks the cache.
package com.metrolist.music.saraswati.family

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
import org.junit.Test
import java.io.IOException

class StationsRefresherTest {
    private lateinit var hub: FakeHub
    private lateinit var family: FakeStore
    private lateinit var cache: FakeStationsCache
    private lateinit var refresher: StationsRefresher
    private lateinit var scope: CoroutineScope

    private val old = Station("s1", "Old", "video000001", 0)
    private val fresh = Station("s2", "Fresh", "video000002", 1)

    // The wall clock the 401 record is kept by (SessionVerdict).
    private val wall = TestClock(nowMs = 1_000_000)

    @Before
    fun setUp() {
        hub = FakeHub()
        family = FakeStore().apply { session = FamilySession("device-1", "the-session-token") }
        cache = FakeStationsCache(listOf(old))
        refresher = StationsRefresher(hub, family, cache, noThrottle(), wall.reading)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun refresh() = runBlocking { refresher.refresh() }

    @Test
    fun `a paired phone fetches the list with its own session and caches it`() {
        hub.stations = listOf(fresh)

        refresh()

        assertEquals(listOf("getStations(the-session-token)"), hub.callsTo("getStations"))
        assertEquals(listOf(fresh), cache.snapshot())
    }

    @Test
    fun `a phone with no session does nothing`() {
        family.session = null
        hub.stations = listOf(fresh)

        refresh()

        assertTrue(hub.callsTo("getStations").isEmpty())
        assertEquals(listOf(old), cache.snapshot())
    }

    @Test
    fun `the owner's phone, which holds only an admin key, does nothing`() {
        family.session = null
        family.admin = TEST_ADMIN_KEY

        refresh()

        assertTrue(hub.callsTo("getStations").isEmpty())
    }

    @Test
    fun `a pending claim does nothing`() {
        family.session = null
        family.claim = PendingClaim("device-1", "secret")

        refresh()

        assertTrue(hub.callsTo("getStations").isEmpty())
    }

    @Test
    fun `any hub failure keeps the cache`() {
        for (failure in listOf<Throwable>(OFFLINE, hubFailure(401), hubFailure(404), hubFailure(429), hubFailure(500))) {
            hub.failWith("getStations", failure)

            refresh()

            assertEquals(listOf(old), cache.snapshot())
        }
    }

    @Test
    fun `an empty list keeps the cache`() {
        hub.stations = emptyList()

        refresh()

        assertEquals(listOf(old), cache.snapshot())
    }

    @Test
    fun `a storage error on either side does not throw`() {
        hub.stations = listOf(fresh)
        cache.failWrites = IOException("disk full")
        refresh()

        family.failReads = IOException("keystore unavailable")
        refresh()

        assertEquals(listOf(old), cache.snapshot())
    }

    @Test
    fun `a refresh asked for while one is running does not send a second request`() {
        val inFlight = CompletableDeferred<Unit>()
        hub.stationsGate = inFlight
        hub.stations = listOf(fresh)
        val first = scope.launch { refresher.refresh() }
        runBlocking { while (hub.callsTo("getStations").isEmpty()) kotlinx.coroutines.delay(5) }

        // A second call must come back at once; if it queued behind the gate this would time out.
        runBlocking { withTimeout(5_000) { refresher.refresh() } }
        inFlight.complete(Unit)
        runBlocking { first.join() }

        assertEquals(1, hub.callsTo("getStations").size)
        assertEquals(listOf(fresh), cache.snapshot())
    }

    @Test
    fun `it can be asked again once the last one has finished`() {
        refresh()
        refresh()

        assertEquals(2, hub.callsTo("getStations").size)
    }

    @Test
    fun `an unanswering hub holds only its own caller, and cancelling that caller frees it`() {
        hub.stationsGate = CompletableDeferred()
        val job = scope.launch { refresher.refresh() }
        runBlocking { while (hub.callsTo("getStations").isEmpty()) kotlinx.coroutines.delay(5) }

        job.cancel()
        runBlocking { job.join() }
        hub.stationsGate = null
        refresh()

        assertEquals(2, hub.callsTo("getStations").size)
    }

    // SARASWATI: fix wave A (M3) — a revoked phone forgets its session so it can join again.
    @Test
    fun `the third counted 401 clears the session and device id and leaves the cache`() {
        hub.failWith("getStations", hubFailure(401))

        repeat(SessionVerdict.STRIKES_TO_CLEAR) {
            refresh()
            wall.advance(SessionVerdict.MIN_GAP_MS)
        }

        assertEquals(listOf("device-1"), family.cleared.toList())
        assertEquals(null, family.session)
        assertEquals(listOf(old), cache.snapshot())
    }

    @Test
    fun `no other failure clears the session`() {
        for (failure in listOf(OFFLINE, hubFailure(404), hubFailure(429), hubFailure(500), hubFailure(503))) {
            hub.failWith("getStations", failure)

            refresh()

            assertEquals(failure.toString(), FamilySession("device-1", "the-session-token"), family.session)
        }
        assertTrue(family.cleared.isEmpty())
    }
    // --- SARASWATI: fix wave B1 — at most one request a minute on resume ---------------------------------

    private val clock = TestClock(nowMs = 5_000)

    private fun throttledRefresher() = StationsRefresher(hub, family, cache, ResumeThrottle(ResumeThrottle.RESUME_INTERVAL_MS, clock.reading))

    @Test
    fun `the first refresh after the process starts runs`() {
        hub.stations = listOf(fresh)

        runBlocking { throttledRefresher().refresh() }

        assertEquals(1, hub.callsTo("getStations").size)
        assertEquals(listOf(fresh), cache.snapshot())
    }

    @Test
    fun `a second refresh inside a minute makes no request and leaves the cache`() {
        val throttled = throttledRefresher()
        hub.stations = listOf(fresh)
        runBlocking { throttled.refresh() }
        hub.stations = listOf(old)
        clock.advance(59_999)

        runBlocking { throttled.refresh() }

        assertEquals(1, hub.callsTo("getStations").size)
        assertEquals(listOf(fresh), cache.snapshot())
    }

    @Test
    fun `a minute later it asks again`() {
        val throttled = throttledRefresher()
        runBlocking { throttled.refresh() }
        hub.stations = listOf(fresh)
        clock.advance(60_000)

        runBlocking { throttled.refresh() }

        assertEquals(2, hub.callsTo("getStations").size)
        assertEquals(listOf(fresh), cache.snapshot())
    }

    @Test
    fun `a failed attempt still counts`() {
        val throttled = throttledRefresher()
        hub.failWith("getStations", OFFLINE)
        runBlocking { throttled.refresh() }
        hub.heal("getStations")
        clock.advance(10_000)

        runBlocking { throttled.refresh() }

        assertEquals(1, hub.callsTo("getStations").size)
    }

    @Test
    fun `a resume with no session does not use up the window`() {
        val throttled = throttledRefresher()
        family.session = null
        runBlocking { throttled.refresh() }
        family.session = FamilySession("device-1", "the-session-token")
        clock.advance(1_000)

        runBlocking { throttled.refresh() }

        assertEquals(1, hub.callsTo("getStations").size)
    }

    @Test
    fun `the owner's phone makes no request and does not use up the window`() {
        val throttled = throttledRefresher()
        family.session = null
        family.admin = TEST_ADMIN_KEY
        runBlocking { throttled.refresh() }
        assertTrue(hub.callsTo("getStations").isEmpty())

        family.admin = null
        family.session = FamilySession("device-1", "the-session-token")
        clock.advance(1_000)
        runBlocking { throttled.refresh() }

        assertEquals(1, hub.callsTo("getStations").size)
    }

    @Test
    fun `a refresh that is refused by the throttle never takes the in-flight lock`() {
        val throttled = throttledRefresher()
        runBlocking { throttled.refresh() }
        clock.advance(1_000)
        hub.stationsGate = CompletableDeferred()

        // If the refused call held the lock, or waited for the gate, this would time out.
        runBlocking { withTimeout(5_000) { throttled.refresh() } }
        hub.stationsGate = null
        clock.advance(60_000)
        runBlocking { withTimeout(5_000) { throttled.refresh() } }

        assertEquals(2, hub.callsTo("getStations").size)
    }
}

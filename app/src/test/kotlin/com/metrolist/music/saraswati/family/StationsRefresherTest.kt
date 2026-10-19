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

    @Before
    fun setUp() {
        hub = FakeHub()
        family = FakeStore().apply { session = FamilySession("device-1", "the-session-token") }
        cache = FakeStationsCache(listOf(old))
        refresher = StationsRefresher(hub, family, cache)
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
}

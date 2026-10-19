/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B2 — the hub answers one generic 401 for every failure, so
// a phone forgets its session only after three counted 401s in a row, each at
// least ten minutes after the one before. These tests drive the two real callers
// (the role refresh and the stations refresh) against a fake wall clock and pin
// each rule: one and two never clear, three spaced clear, three inside ten
// minutes do not, a 2xx resets the run, and the record outlives a restart.
package com.metrolist.music.saraswati.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.FamilyUnauthorizedAtKey
import com.metrolist.music.constants.FamilyUnauthorizedCountKey
import com.metrolist.music.saraswati.saral.GrandparentRoleSync
import com.metrolist.music.saraswati.security.CookieVault
import com.metrolist.music.saraswati.security.SoftwareKeyProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SessionVerdictTest {
    private lateinit var hub: FakeHub
    private lateinit var family: FakeStore
    private lateinit var roles: FakeRoleStore
    private lateinit var cache: FakeStationsCache
    private lateinit var sync: GrandparentRoleSync
    private lateinit var stations: StationsRefresher
    private val wall = TestClock(nowMs = 50_000_000)
    private val session = FamilySession("device-1", "the-session-token")
    private val gap = SessionVerdict.MIN_GAP_MS

    @Before
    fun setUp() {
        hub = FakeHub()
        family = FakeStore().apply { session = this@SessionVerdictTest.session }
        roles = FakeRoleStore(grandparent = true)
        cache = FakeStationsCache(listOf(Station("s1", "Old", "video000001", 0)))
        sync = GrandparentRoleSync(hub, family, roles, noThrottle(), wall.reading)
        stations = StationsRefresher(hub, family, cache, noThrottle(), wall.reading)
    }

    private fun roleRefresh() = runBlocking { sync.refresh() }

    private fun stationsRefresh() = runBlocking { stations.refresh() }

    private fun unauthorized() = hub.failWith("getMe", hubFailure(401))

    @Test
    fun `the constants are three strikes and ten minutes`() {
        assertEquals(3, SessionVerdict.STRIKES_TO_CLEAR)
        assertEquals(10 * 60_000L, SessionVerdict.MIN_GAP_MS)
    }

    @Test
    fun `one 401 never clears the session and is recorded`() {
        unauthorized()

        roleRefresh()

        assertEquals(session, family.session)
        assertTrue(family.cleared.isEmpty())
        assertEquals(UnauthorizedStrikes(1, wall.nowMs), family.strikes)
    }

    @Test
    fun `two spaced 401s do not clear it either`() {
        unauthorized()

        roleRefresh()
        wall.advance(gap)
        roleRefresh()

        assertEquals(session, family.session)
        assertTrue(family.cleared.isEmpty())
        assertEquals(UnauthorizedStrikes(2, wall.nowMs), family.strikes)
    }

    @Test
    fun `three 401s, each ten minutes after the last counted one, clear the session and the device id`() {
        unauthorized()

        roleRefresh()
        wall.advance(gap)
        roleRefresh()
        wall.advance(gap)
        roleRefresh()

        assertNull(family.session)
        assertEquals(listOf("device-1"), family.cleared.toList())
        assertEquals("the record goes with the session", UnauthorizedStrikes.NONE, family.strikes)
    }

    @Test
    fun `a gap longer than ten minutes counts too`() {
        unauthorized()

        roleRefresh()
        wall.advance(gap + 1)
        roleRefresh()
        wall.advance(3 * gap)
        roleRefresh()

        assertNull(family.session)
    }

    @Test
    fun `a 401 one millisecond short of ten minutes is not counted, and exactly ten minutes is`() {
        unauthorized()

        roleRefresh()
        wall.advance(gap - 1)
        roleRefresh()
        assertEquals(1, family.strikes.count)

        wall.advance(1)
        roleRefresh()
        assertEquals(UnauthorizedStrikes(2, wall.nowMs), family.strikes)
        assertEquals(session, family.session)
    }

    @Test
    fun `three 401s inside ten minutes do not clear the session`() {
        unauthorized()

        roleRefresh()
        wall.advance(gap / 4)
        roleRefresh()
        wall.advance(gap / 4)
        roleRefresh()

        assertEquals(session, family.session)
        assertEquals(1, family.strikes.count)
    }

    @Test
    fun `a burst of 401s inside ten minutes is one strike`() {
        unauthorized()

        repeat(5) {
            roleRefresh()
            wall.advance(gap / 10)
        }

        assertEquals(session, family.session)
        assertEquals(1, family.strikes.count)
    }

    @Test
    fun `the role refresh and the stations refresh answering together count one outage once`() {
        unauthorized()
        hub.failWith("getStations", hubFailure(401))

        roleRefresh()
        stationsRefresh()

        assertEquals(1, family.strikes.count)
        assertEquals(session, family.session)
    }

    @Test
    fun `strikes from the two callers add up`() {
        unauthorized()
        hub.failWith("getStations", hubFailure(401))

        roleRefresh()
        wall.advance(gap)
        stationsRefresh()
        wall.advance(gap)
        roleRefresh()

        assertNull(family.session)
    }

    @Test
    fun `a 2xx resets the count and the time`() {
        unauthorized()
        roleRefresh()
        wall.advance(gap)
        roleRefresh()
        assertEquals(2, family.strikes.count)

        hub.heal("getMe")
        roleRefresh()
        assertEquals(UnauthorizedStrikes.NONE, family.strikes)

        // So two more 401s are the first and second again, not the third.
        unauthorized()
        wall.advance(gap)
        roleRefresh()
        wall.advance(gap)
        roleRefresh()
        assertEquals(session, family.session)
        assertEquals(2, family.strikes.count)
    }

    @Test
    fun `a successful stations answer resets a run of role 401s`() {
        unauthorized()
        roleRefresh()
        wall.advance(gap)
        roleRefresh()

        stationsRefresh()

        assertEquals(UnauthorizedStrikes.NONE, family.strikes)
    }

    @Test
    fun `an answer that is not a 401 leaves the record as it was`() {
        unauthorized()
        roleRefresh()
        val before = family.strikes
        wall.advance(gap)

        for (failure in listOf(OFFLINE, hubFailure(404), hubFailure(429), hubFailure(500), hubFailure(503), hubFailure(403))) {
            hub.failWith("getMe", failure)
            roleRefresh()
            assertEquals(failure.toString(), before, family.strikes)
        }
        assertEquals(session, family.session)
    }

    @Test
    fun `a 401 then an outage then two more 401s still counts three`() {
        unauthorized()
        roleRefresh()
        wall.advance(gap)
        hub.failWith("getMe", OFFLINE)
        roleRefresh()
        wall.advance(gap)
        unauthorized()
        roleRefresh()
        wall.advance(gap)
        roleRefresh()

        assertNull(family.session)
    }

    @Test
    fun `a clock set back restarts the gap instead of freezing the count`() {
        unauthorized()
        roleRefresh()
        wall.advance(gap)
        roleRefresh()
        assertEquals(2, family.strikes.count)

        wall.advance(-30 * 24 * 60 * 60_000L)
        roleRefresh()
        assertEquals("the backwards 401 is not counted", 2, family.strikes.count)
        assertEquals(session, family.session)
        assertEquals("but the gap now runs from the corrected time", wall.nowMs, family.strikes.lastCountedAtMs)

        wall.advance(gap)
        roleRefresh()
        assertNull(family.session)
    }

    @Test
    fun `a storage error never throws and never clears the session`() {
        unauthorized()
        family.failWrites = IOException("disk full")
        for (i in 1..4) {
            roleRefresh()
            wall.advance(gap)
        }
        assertEquals(session, family.session)

        family.failWrites = null
        family.failReads = IOException("unreadable")
        for (i in 1..4) {
            roleRefresh()
            wall.advance(gap)
        }
        assertEquals(session, family.session)
        assertTrue(family.cleared.isEmpty())
    }

    @Test
    fun `clearing never touches the admin key or the role cache`() {
        unauthorized()

        repeat(3) {
            roleRefresh()
            wall.advance(gap)
        }

        assertNull(family.session)
        assertTrue("the Saral cache stays", roles.grandparent)
        assertTrue(roles.writes.isEmpty())
    }

    @Test
    fun `a new pairing starts with a clean record`() {
        unauthorized()
        roleRefresh()
        wall.advance(gap)
        roleRefresh()
        assertEquals(2, family.strikes.count)

        runBlocking { family.completeClaim("device-2", "new-token") }

        assertEquals(UnauthorizedStrikes.NONE, family.strikes)
    }

    @Test
    fun `a health report that the hub accepted resets the run, a refused one does not count`() {
        unauthorized()
        roleRefresh()
        assertEquals(1, family.strikes.count)
        val reporter =
            HealthReporter(hub, family, noThrottle(), versionCode = 1, versionName = "1.0", timezone = { "UTC" }, io = Dispatchers.Unconfined)

        hub.failWith("reportHealth", hubFailure(401))
        runBlocking { reporter.report() }
        assertEquals("a refused heartbeat is not a strike and not a reset", 1, family.strikes.count)

        hub.heal("reportHealth")
        runBlocking { reporter.report() }
        assertEquals(UnauthorizedStrikes.NONE, family.strikes)
    }

    // --- SARASWATI: fix wave B3 — a verdict is about one session, and is atomic ------------------------------

    @Test
    fun `an answer about a session the phone no longer holds changes nothing`() {
        family.strikes = UnauthorizedStrikes(2, wall.nowMs - gap)
        family.session = FamilySession("device-2", "the-new-token")
        val stale = session

        runBlocking { judgeSessionAnswer(family, stale, Result.success(Unit), wall.reading) }
        assertEquals("a success for the old session does not wipe the new session's record", 2, family.strikes.count)

        runBlocking { judgeSessionAnswer(family, stale, Result.failure<Unit>(hubFailure(401)), wall.reading) }
        assertEquals("nor does its 401 count against the new one", UnauthorizedStrikes(2, wall.nowMs - gap), family.strikes)
        assertEquals(FamilySession("device-2", "the-new-token"), family.session)
        assertTrue(family.cleared.isEmpty())
    }

    @Test
    fun `an answer when the stored session cannot be opened right now changes nothing`() {
        family.strikes = UnauthorizedStrikes(2, wall.nowMs - gap)
        family.failReads = IOException("Keystore unavailable")

        runBlocking { judgeSessionAnswer(family, session, Result.success(Unit), wall.reading) }

        assertEquals(2, family.strikes.count)
    }

    @Test
    fun `a health report that the hub accepted waits for a verdict that is under way`() {
        family.strikes = UnauthorizedStrikes(2, wall.nowMs - gap)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        family.onReadStrikes = {
            inside.complete(Unit)
            release.await()
        }
        hub.failWith("getMe", hubFailure(401))
        val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val verdict = background.launch { sync.refresh() }
            runBlocking { withTimeout(5_000) { inside.await() } }
            val reporter =
                HealthReporter(hub, family, noThrottle(), versionCode = 1, versionName = "1.0", timezone = { "UTC" }, io = Dispatchers.Default)
            val report = background.launch { reporter.report() }
            runBlocking { delay(300) }

            assertEquals("the reset did not slip in between the verdict's read and its write", 2, family.strikes.count)

            release.complete(Unit)
            runBlocking {
                withTimeout(5_000) {
                    verdict.join()
                    report.join()
                }
            }
            assertNull("the verdict ran to its end: the third counted 401 cleared the session", family.session)
            assertEquals(UnauthorizedStrikes.NONE, family.strikes)
        } finally {
            background.cancel()
        }
    }
}

class DataStoreSessionVerdictTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreFamilyStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        store = DataStoreFamilyStore(dataStore, CookieVault(SoftwareKeyProvider()))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun raw(): Preferences = runBlocking { dataStore.data.first() }

    @Test
    fun `no strikes on a fresh phone`() =
        runBlocking {
            assertEquals(UnauthorizedStrikes.NONE, store.unauthorizedStrikes())
        }

    @Test
    fun `the count and the time are kept in plain preferences, not sealed`() =
        runBlocking {
            store.saveUnauthorizedStrikes(UnauthorizedStrikes(2, 1_234_567L))

            assertEquals(UnauthorizedStrikes(2, 1_234_567L), store.unauthorizedStrikes())
            assertEquals(2, raw()[FamilyUnauthorizedCountKey])
            assertEquals(1_234_567L, raw()[FamilyUnauthorizedAtKey])
        }

    @Test
    fun `a reset removes both`() =
        runBlocking {
            store.saveUnauthorizedStrikes(UnauthorizedStrikes(2, 1_234_567L))

            store.resetUnauthorizedStrikes()

            assertEquals(UnauthorizedStrikes.NONE, store.unauthorizedStrikes())
            assertNull(raw()[FamilyUnauthorizedCountKey])
            assertNull(raw()[FamilyUnauthorizedAtKey])
        }

    @Test
    fun `clearing the session and completing a claim both drop the record`() =
        runBlocking {
            store.completeClaim("device-1", "tok")
            store.saveUnauthorizedStrikes(UnauthorizedStrikes(2, 5L))

            store.clearSession("device-1")
            assertEquals(UnauthorizedStrikes.NONE, store.unauthorizedStrikes())

            store.saveUnauthorizedStrikes(UnauthorizedStrikes(2, 5L))
            store.completeClaim("device-2", "tok2")
            assertEquals(UnauthorizedStrikes.NONE, store.unauthorizedStrikes())
        }

    @Test
    fun `clearing another device's session leaves the record`() =
        runBlocking {
            store.completeClaim("device-1", "tok")
            store.saveUnauthorizedStrikes(UnauthorizedStrikes(2, 5L))

            store.clearSession("device-9")

            assertEquals(UnauthorizedStrikes(2, 5L), store.unauthorizedStrikes())
            assertTrue(store.hasStoredSession())
        }

    @Test
    fun `a record survives a new store over the same file, which is a restart`() =
        runBlocking {
            store.completeClaim("device-1", "tok")
            store.saveUnauthorizedStrikes(UnauthorizedStrikes(2, 99L))

            val restarted = DataStoreFamilyStore(dataStore, CookieVault(SoftwareKeyProvider()))

            assertEquals(UnauthorizedStrikes(2, 99L), restarted.unauthorizedStrikes())
        }

    @Test
    fun `the raw session slot is seen without opening it`() =
        runBlocking {
            assertFalse(store.hasStoredSession())
            store.completeClaim("device-1", "tok")
            assertTrue(store.hasStoredSession())

            // A different key (this device's Keystore is not answering the way it did) cannot open the token...
            val unreadable = DataStoreFamilyStore(dataStore, CookieVault(SoftwareKeyProvider()))
            assertNull(unreadable.session())
            // ...but the slot is still seen.
            assertTrue(unreadable.hasStoredSession())

            store.clearSession("device-1")
            assertFalse(store.hasStoredSession())
        }
}

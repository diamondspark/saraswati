/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the Saral switch is a cache of the role the hub holds
// for this phone. These tests pin the fail-safe rule: only the hub's own
// answer ever changes it, and nothing that goes wrong (no signal, a phone
// that is unpaired, pending, revoked or the owner's, a storage error) turns
// Saral off.
package com.metrolist.music.saraswati.saral

import com.metrolist.music.saraswati.family.DeviceRole
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FakeRoleStore
import com.metrolist.music.saraswati.family.FakeStore
import com.metrolist.music.saraswati.family.FamilySession
import com.metrolist.music.saraswati.family.OFFLINE
import com.metrolist.music.saraswati.family.PendingClaim
import com.metrolist.music.saraswati.family.TEST_ADMIN_KEY
import com.metrolist.music.saraswati.family.ResumeThrottle
import com.metrolist.music.saraswati.family.TestClock
import com.metrolist.music.saraswati.family.noThrottle
import com.metrolist.music.saraswati.family.hubFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GrandparentRoleSyncTest {
    private lateinit var hub: FakeHub
    private lateinit var family: FakeStore
    private lateinit var roles: FakeRoleStore
    private lateinit var sync: GrandparentRoleSync

    @Before
    fun setUp() {
        hub = FakeHub()
        family = FakeStore().apply { session = FamilySession("device-1", "the-session-token") }
        roles = FakeRoleStore()
        sync = GrandparentRoleSync(hub, family, roles, noThrottle())
    }

    private fun refresh() = runBlocking { sync.refresh() }

    @Test
    fun `the hub saying grandparent turns Saral on`() {
        hub.meRole = DeviceRole.GRANDPARENT

        assertEquals(DeviceRole.GRANDPARENT, refresh())

        assertTrue(roles.grandparent)
    }

    @Test
    fun `the hub saying adult turns Saral off, which is how the owner switches a phone back`() {
        roles.grandparent = true
        hub.meRole = DeviceRole.ADULT

        assertEquals(DeviceRole.ADULT, refresh())

        assertFalse(roles.grandparent)
    }

    @Test
    fun `it asks the hub with this phone's own session token`() {
        refresh()

        assertEquals(listOf("getMe(the-session-token)"), hub.callsTo("getMe"))
    }

    @Test
    fun `an unreachable hub keeps Saral exactly as it was`() {
        roles.grandparent = true
        hub.failWith("getMe", OFFLINE)

        assertNull(refresh())

        assertTrue(roles.grandparent)
        assertTrue(roles.writes.isEmpty())
    }

    @Test
    fun `any hub failure keeps Saral on, including a revoked phone and a server error`() {
        roles.grandparent = true
        for (status in listOf(401, 404, 429, 500, 503)) {
            family.session = FamilySession("device-1", "the-session-token")
            hub.failWith("getMe", hubFailure(status))

            assertNull(refresh())
            assertTrue("HTTP $status must not leave Saral", roles.grandparent)
        }
    }

    // SARASWATI: fix wave A (M3) — a revoked phone can join again.
    @Test
    fun `a 401 clears the session and the device id so Join works again, and leaves Saral as it was`() {
        roles.grandparent = true
        hub.failWith("getMe", hubFailure(401))

        assertNull(refresh())

        assertEquals(listOf("device-1"), family.cleared.toList())
        assertNull(family.session)
        assertTrue("the role cache stays untouched", roles.grandparent)
        assertTrue(roles.writes.isEmpty())
    }

    @Test
    fun `a 401 on an adult phone also leaves the role cache alone`() {
        roles.grandparent = false
        hub.failWith("getMe", hubFailure(401))

        refresh()

        assertNull(family.session)
        assertFalse(roles.grandparent)
        assertTrue(roles.writes.isEmpty())
    }

    @Test
    fun `no other failure clears the session`() {
        for (failure in listOf(OFFLINE, hubFailure(404), hubFailure(429), hubFailure(500), hubFailure(503), hubFailure(403))) {
            hub.failWith("getMe", failure)

            assertNull(refresh())

            assertEquals(failure.toString(), FamilySession("device-1", "the-session-token"), family.session)
        }
        assertTrue(family.cleared.isEmpty())
    }

    @Test
    fun `a storage error while clearing the session does not throw`() {
        hub.failWith("getMe", hubFailure(401))
        family.failWrites = java.io.IOException("disk full")

        assertNull(refresh())

        assertEquals(FamilySession("device-1", "the-session-token"), family.session)
    }

    // SARASWATI: fix wave A (M2) — the owner's own phone is never Saral.
    @Test
    fun `a phone that holds the admin key is never set to grandparent, whatever the hub says`() {
        family.admin = TEST_ADMIN_KEY
        hub.meRole = DeviceRole.GRANDPARENT

        assertNull(refresh())

        assertFalse(roles.grandparent)
        assertTrue(roles.writes.isEmpty())
        assertTrue("the hub is not even asked", hub.callsTo("getMe").isEmpty())
    }

    @Test
    fun `an owner phone with a session the hub calls grandparent is still not locked in`() {
        family.admin = TEST_ADMIN_KEY
        family.session = FamilySession("device-1", "the-session-token")
        hub.meRole = DeviceRole.GRANDPARENT

        refresh()

        assertFalse(roles.grandparent)
    }

    @Test
    fun `a stale Saral flag on an owner phone is released`() {
        family.admin = TEST_ADMIN_KEY
        roles.grandparent = true

        refresh()

        assertFalse(roles.grandparent)
        assertEquals(listOf(false), roles.writes.toList())
    }

    @Test
    fun `an owner phone whose role store fails does not throw`() {
        family.admin = TEST_ADMIN_KEY
        roles.failReads = java.io.IOException("disk")

        assertNull(refresh())
    }

    @Test
    fun `a phone that is not paired is left alone and the hub is not asked`() {
        family.session = null
        roles.grandparent = true

        assertNull(refresh())

        assertTrue(roles.grandparent)
        assertTrue(hub.callsTo("getMe").isEmpty())
    }

    @Test
    fun `a phone still waiting for approval is left alone`() {
        family.session = null
        family.claim = PendingClaim("device-1", "claim-secret")
        roles.grandparent = true

        assertNull(refresh())

        assertTrue(roles.grandparent)
        assertTrue(hub.callsTo("getMe").isEmpty())
    }

    @Test
    fun `the owner's phone, which holds an admin key and no session, is left alone`() {
        family.session = null
        family.admin = TEST_ADMIN_KEY

        assertNull(refresh())

        assertTrue(hub.callsTo("getMe").isEmpty())
        assertTrue(roles.writes.isEmpty())
    }

    @Test
    fun `a storage error while reading the session keeps Saral and does not throw`() {
        roles.grandparent = true
        family.failReads = java.io.IOException("keystore unavailable")

        assertNull(refresh())

        assertTrue(roles.grandparent)
    }

    @Test
    fun `a storage error while saving the role does not throw`() {
        hub.meRole = DeviceRole.GRANDPARENT
        roles.failWrites = java.io.IOException("disk full")

        assertEquals(DeviceRole.GRANDPARENT, refresh())
    }

    @Test
    fun `an unchanged answer is not written again`() {
        hub.meRole = DeviceRole.GRANDPARENT
        refresh()
        refresh()

        assertEquals(listOf(true), roles.writes.toList())
    }

    @Test
    fun `an answer with no role, or one this build does not know, is no answer`() {
        for (known in listOf(true, false)) {
            roles.grandparent = known
            roles.writes.clear()
            hub.meRole = DeviceRole.ADULT
            hub.meRoleKnown = false

            assertNull(refresh())

            assertEquals("the cache must stay as it was", known, roles.grandparent)
            assertTrue(roles.writes.isEmpty())
        }
    }

    @Test
    fun `a known role after an unknown one is taken`() {
        roles.grandparent = true
        hub.meRole = DeviceRole.ADULT
        hub.meRoleKnown = false
        refresh()

        hub.meRoleKnown = true

        assertEquals(DeviceRole.ADULT, refresh())
        assertFalse(roles.grandparent)
    }
    // --- SARASWATI: fix wave B1 — at most one pair of hub calls a minute on resume -------------------

    private val clock = TestClock(nowMs = 5_000)

    private fun throttledSync() = GrandparentRoleSync(hub, family, roles, ResumeThrottle(ResumeThrottle.RESUME_INTERVAL_MS, clock.reading))

    private fun GrandparentRoleSync.refreshNow(force: Boolean = false) = runBlocking { refresh(force) }

    @Test
    fun `the first refresh after the process starts runs`() {
        hub.meRole = DeviceRole.GRANDPARENT

        assertEquals(DeviceRole.GRANDPARENT, throttledSync().refreshNow())

        assertEquals(1, hub.callsTo("getMe").size)
    }

    @Test
    fun `a second refresh inside a minute makes no hub call and changes nothing`() {
        val throttled = throttledSync()
        hub.meRole = DeviceRole.GRANDPARENT
        throttled.refreshNow()
        hub.meRole = DeviceRole.ADULT
        clock.advance(59_999)

        assertNull(throttled.refreshNow())

        assertEquals(1, hub.callsTo("getMe").size)
        assertTrue("the cached role is as the first answer left it", roles.grandparent)
    }

    @Test
    fun `a minute later it asks again`() {
        val throttled = throttledSync()
        hub.meRole = DeviceRole.GRANDPARENT
        throttled.refreshNow()
        hub.meRole = DeviceRole.ADULT
        clock.advance(60_000)

        assertEquals(DeviceRole.ADULT, throttled.refreshNow())

        assertEquals(2, hub.callsTo("getMe").size)
        assertFalse(roles.grandparent)
    }

    @Test
    fun `a failed attempt still counts, so a struggling hub is not hammered on every resume`() {
        val throttled = throttledSync()
        hub.failWith("getMe", OFFLINE)
        throttled.refreshNow()
        hub.heal("getMe")
        clock.advance(10_000)

        assertNull(throttled.refreshNow())

        assertEquals(1, hub.callsTo("getMe").size)
    }

    @Test
    fun `a forced refresh, as after an approval, ignores the throttle`() {
        val throttled = throttledSync()
        throttled.refreshNow()

        assertEquals(DeviceRole.ADULT, throttled.refreshNow(force = true))

        assertEquals(2, hub.callsTo("getMe").size)
    }

    @Test
    fun `a forced refresh does not use up the window of the next resume`() {
        val throttled = throttledSync()
        throttled.refreshNow(force = true)

        throttled.refreshNow()

        assertEquals(2, hub.callsTo("getMe").size)
    }

    @Test
    fun `a resume with no session to ask with does not use up the window`() {
        val throttled = throttledSync()
        family.session = null
        throttled.refreshNow()
        family.session = FamilySession("device-1", "the-session-token")
        clock.advance(1_000)

        throttled.refreshNow()

        assertEquals("the phone was approved a second later and is asked straight away", 1, hub.callsTo("getMe").size)
    }

    @Test
    fun `the owner's phone makes no hub call and does not use up the window`() {
        val throttled = throttledSync()
        family.admin = TEST_ADMIN_KEY
        throttled.refreshNow()
        assertTrue(hub.callsTo("getMe").isEmpty())

        family.admin = null
        clock.advance(1_000)
        throttled.refreshNow()

        assertEquals(1, hub.callsTo("getMe").size)
    }

    @Test
    fun `the owner's phone is still released from a stale Saral flag while resumes are throttled`() {
        val throttled = throttledSync()
        family.admin = TEST_ADMIN_KEY
        roles.grandparent = true

        throttled.refreshNow()
        roles.grandparent = true
        clock.advance(1_000)
        throttled.refreshNow()

        assertFalse("a local check, never throttled", roles.grandparent)
    }
}

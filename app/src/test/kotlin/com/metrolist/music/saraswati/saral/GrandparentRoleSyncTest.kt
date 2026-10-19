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
        sync = GrandparentRoleSync(hub, family, roles)
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
            hub.failWith("getMe", hubFailure(status))

            assertNull(refresh())
            assertTrue("HTTP $status must not leave Saral", roles.grandparent)
        }
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
}

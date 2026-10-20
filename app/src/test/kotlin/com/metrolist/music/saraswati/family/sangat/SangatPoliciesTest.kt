/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — the reconnect rule, the approval rule and car mode's own state.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.HubException
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.LiveSangatRoom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SangatPoliciesTest {
    @Test
    fun `401 and 503 on upgrade never reconnect`() {
        assertFalse(SangatReconnectPolicy.shouldReconnect(401, null))
        assertFalse(SangatReconnectPolicy.shouldReconnect(503, null))
    }

    @Test
    fun `close codes 4001 and 4003 never reconnect`() {
        assertFalse(SangatReconnectPolicy.shouldReconnect(null, 4001))
        assertFalse(SangatReconnectPolicy.shouldReconnect(null, 4003))
    }

    @Test
    fun `a network failure reconnects`() {
        assertTrue(SangatReconnectPolicy.shouldReconnect(null, null))
    }

    @Test
    fun `an idle close, a busy hub and a rate limit are retried, the last one not at once`() {
        assertTrue(SangatReconnectPolicy.shouldReconnect(null, 1001))
        assertTrue(SangatReconnectPolicy.shouldReconnect(null, 1006))
        assertTrue(SangatReconnectPolicy.shouldReconnect(502, null))
        assertTrue(SangatReconnectPolicy.shouldReconnect(429, null))
        assertEquals(60_000L, SangatReconnectPolicy.minimumBackoffMs(429))
        assertEquals(0L, SangatReconnectPolicy.minimumBackoffMs(502))
        assertEquals(0L, SangatReconnectPolicy.minimumBackoffMs(null))
    }

    @Test
    fun `car mode auto-approves joins and suggestions whatever the preference`() {
        assertTrue(SangatApprovalPolicy.autoApprove(prefEnabled = false, carMode = true))
        assertTrue(SangatApprovalPolicy.autoApprove(prefEnabled = true, carMode = true))
    }

    @Test
    fun `outside car mode the preference decides`() {
        assertTrue(SangatApprovalPolicy.autoApprove(prefEnabled = true, carMode = false))
        assertFalse(SangatApprovalPolicy.autoApprove(prefEnabled = false, carMode = false))
    }

    private class FakeSangatHub : SangatHubApi {
        val carCalls = mutableListOf<Triple<String, String, Boolean>>()
        var carResult: Result<Unit> = Result.success(Unit)

        override suspend fun getLiveSangat(sessionToken: String): Result<List<LiveSangatRoom>> = Result.success(emptyList())

        override suspend fun setSangatCarMode(
            sessionToken: String,
            roomCode: String,
            carMode: Boolean,
        ): Result<Unit> {
            carCalls += Triple(sessionToken, roomCode, carMode)
            return carResult
        }
    }

    private fun identity(token: String?) =
        object : HubIdentity {
            override suspend fun sessionToken() = token

            override suspend fun deviceId() = token?.let { "dev-1" }
        }

    @Test
    fun `marking a hosted room sends car mode on and turns active on`() =
        runBlocking {
            val hub = FakeSangatHub()
            val carMode = SangatCarMode(hub, identity("sess"))
            assertFalse(carMode.active.value)

            assertTrue(carMode.markHostedRoom("ABCD").isSuccess)

            assertEquals(listOf(Triple("sess", "ABCD", true)), hub.carCalls)
            assertTrue(carMode.active.value)
            carMode.end()
            assertFalse(carMode.active.value)
        }

    @Test
    fun `a hub that refuses leaves car mode off`() =
        runBlocking {
            val hub = FakeSangatHub().apply { carResult = Result.failure(HubException(403, "no")) }
            val carMode = SangatCarMode(hub, identity("sess"))

            assertTrue(carMode.markHostedRoom("ABCD").isFailure)
            assertFalse(carMode.active.value)
        }

    @Test
    fun `without a session nothing is sent and car mode stays off`() =
        runBlocking {
            val hub = FakeSangatHub()
            val carMode = SangatCarMode(hub, identity(null))

            assertTrue(carMode.markHostedRoom("ABCD").isFailure)
            assertTrue(hub.carCalls.isEmpty())
            assertFalse(carMode.active.value)
        }
}

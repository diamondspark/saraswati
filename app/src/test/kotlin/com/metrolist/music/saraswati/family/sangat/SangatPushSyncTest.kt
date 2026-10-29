/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — when the phone tells the hub its FCM token, without Firebase or Android.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.HubException
import com.metrolist.music.saraswati.family.sangat.SangatPushSync.Outcome
import com.metrolist.music.saraswati.family.FamilyFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SangatPushSyncTest {
    private class FakePushHub : PushHubApi {
        val registered = mutableListOf<Pair<String, String>>()
        var answer: Result<Unit> = Result.success(Unit)

        override suspend fun registerPushToken(
            sessionToken: String,
            token: String,
        ): Result<Unit> {
            registered += sessionToken to token
            return answer
        }

        override suspend fun unregisterPushToken(sessionToken: String): Result<Unit> = Result.success(Unit)
    }

    private class MemoryStore : PushRegistrationStore {
        var value: PushRegistration? = null
        var writes = 0

        override suspend fun read() = value

        override suspend fun write(registration: PushRegistration) {
            writes++
            value = registration
        }
    }

    private val hub = FakePushHub()
    private val store = MemoryStore()
    private var configured = true
    private var session: String? = "sess"
    private var device: String? = "dev-1"
    private var sangatOn = true
    private var token: String? = "tok-1"
    private var now = 1_000_000L

    private fun sync() =
        SangatPushSync(
            configured = configured,
            hub = hub,
            store = store,
            sessionToken = { session },
            deviceId = { device },
            sangatEnabled = { sangatOn },
            fetchToken = { token },
            clock = { now },
        )

    @Test
    fun `not configured makes no call`() =
        runBlocking {
            configured = false
            assertEquals(Outcome.NOT_CONFIGURED, sync().ensureRegistered())
            assertEquals(Outcome.NOT_CONFIGURED, sync().onNewToken("t"))
            assertTrue(hub.registered.isEmpty())
        }

    @Test
    fun `not paired makes no call`() =
        runBlocking {
            session = null
            assertEquals(Outcome.NOT_PAIRED, sync().ensureRegistered())
            assertEquals(Outcome.NOT_PAIRED, sync().onNewToken("t"))
            assertTrue(hub.registered.isEmpty())
        }

    @Test
    fun `Sangat off makes no call`() =
        runBlocking {
            sangatOn = false
            assertEquals(Outcome.SANGAT_OFF, sync().ensureRegistered())
            assertEquals(Outcome.SANGAT_OFF, sync().onNewToken("t"))
            assertTrue(hub.registered.isEmpty())
        }

    @Test
    fun `no token from Firebase makes no call`() =
        runBlocking {
            token = null
            assertEquals(Outcome.NO_TOKEN, sync().ensureRegistered())
            assertTrue(hub.registered.isEmpty())
        }

    @Test
    fun `a first token is registered with the session bearer and its hash stored`() =
        runBlocking {
            assertEquals(Outcome.REGISTERED, sync().ensureRegistered())
            assertEquals(listOf("sess" to "tok-1"), hub.registered)
            assertEquals(PushRegistration(SangatPushSync.fingerprintOf("dev-1", "tok-1"), now), store.value)
        }

    @Test
    fun `the same token within seven days is not registered again`() =
        runBlocking {
            val sync = sync()
            sync.ensureRegistered()
            now += SangatPushSync.REREGISTER_AFTER_MS - 1
            assertEquals(Outcome.UP_TO_DATE, sync.ensureRegistered())
            assertEquals(1, hub.registered.size)
        }

    @Test
    fun `the same token after seven days is registered again`() =
        runBlocking {
            val sync = sync()
            sync.ensureRegistered()
            now += SangatPushSync.REREGISTER_AFTER_MS
            assertEquals(Outcome.REGISTERED, sync.ensureRegistered())
            assertEquals(2, hub.registered.size)
            assertEquals(now, store.value?.registeredAt)
        }

    @Test
    fun `a changed token is registered at once`() =
        runBlocking {
            val sync = sync()
            sync.ensureRegistered()
            token = "tok-2"
            now += 1_000
            assertEquals(Outcome.REGISTERED, sync.ensureRegistered())
            assertEquals(listOf("tok-1", "tok-2"), hub.registered.map { it.second })
            assertEquals(SangatPushSync.fingerprintOf("dev-1", "tok-2"), store.value?.tokenHash)
        }

    @Test
    fun `a failed call stores nothing, so the next try calls again`() =
        runBlocking {
            hub.answer = Result.failure(HubException(429, "x"))
            val sync = sync()
            assertEquals(Outcome.FAILED, sync.ensureRegistered())
            assertNull(store.value)
            assertEquals(0, store.writes)

            hub.answer = Result.success(Unit)
            assertEquals(Outcome.REGISTERED, sync.ensureRegistered())
            assertEquals(2, hub.registered.size)
            assertEquals(1, store.writes)
        }

    @Test
    fun `a new token from Firebase is registered even when one is stored`() =
        runBlocking {
            val sync = sync()
            sync.ensureRegistered()
            assertEquals(Outcome.REGISTERED, sync.onNewToken("tok-9"))
            assertEquals("tok-9", hub.registered.last().second)
            assertEquals(SangatPushSync.fingerprintOf("dev-1", "tok-9"), store.value?.tokenHash)
        }

    @Test
    fun `the same token under a new device id is registered again within seven days`() =
        runBlocking {
            val sync = sync()
            sync.ensureRegistered()
            device = "dev-2" // re-paired: the hub dropped the old device and its token
            now += 1_000
            assertEquals(Outcome.REGISTERED, sync.ensureRegistered())
            assertEquals(2, hub.registered.size)
            assertEquals(SangatPushSync.fingerprintOf("dev-2", "tok-1"), store.value?.tokenHash)
        }

    @Test
    fun `the fingerprint depends on both the device id and the token`() {
        val a = SangatPushSync.fingerprintOf("dev-1", "tok")
        assertEquals(a, SangatPushSync.fingerprintOf("dev-1", "tok"))
        assertFalse(a == SangatPushSync.fingerprintOf("dev-2", "tok"))
        assertFalse(a == SangatPushSync.fingerprintOf("dev-1", "tok2"))
        assertFalse(a == SangatPushSync.fingerprintOf(null, "tok"))
    }

    @Test
    fun `Sangat turning on registers again, but the starting value and staying on do not`() =
        runBlocking {
            fun map(on: Boolean) = FamilyFeature.entries.associateWith { it != FamilyFeature.SANGAT || on }
            val gate = MutableStateFlow(map(true))
            val seen = mutableListOf<Unit>()
            val job = launch(Dispatchers.Unconfined) { SangatPushSync.sangatTurnedOn(gate).collect { seen += it } }
            assertEquals(0, seen.size)
            gate.value = map(false)
            assertEquals(0, seen.size)
            gate.value = map(true)
            assertEquals(1, seen.size)
            gate.value = FamilyFeature.entries.associateWith { true }
            assertEquals(1, seen.size)
            job.cancel()
        }

    @Test
    fun `the stored value is a hash, not the token`() =
        runBlocking {
            sync().ensureRegistered()
            val hash = store.value!!.tokenHash
            assertFalse("tok-1" in hash)
            assertEquals(64, hash.length)
        }

    @Test
    fun `a throwing session or gate reads as not eligible, never as a crash`() =
        runBlocking {
            val sync =
                SangatPushSync(true, hub, store, { throw IllegalStateException("keystore") }, { device }, { true }, { token }, { now })
            assertEquals(Outcome.NOT_PAIRED, sync.ensureRegistered())
            assertTrue(hub.registered.isEmpty())
        }

    @Test
    fun `configured needs all four ids and a family application id`() {
        val ok = { id: String -> SangatPushConfig.isConfigured("p", "s", "k", "a", id) }
        assertTrue(ok("dev.diamondspark.saraswati"))
        assertTrue(ok("dev.diamondspark.saraswati.debug"))
        assertFalse(ok("com.metrolist.music"))
        assertFalse(SangatPushConfig.isConfigured("", "s", "k", "a", "dev.diamondspark.saraswati"))
        assertFalse(SangatPushConfig.isConfigured("p", " ", "k", "a", "dev.diamondspark.saraswati"))
        assertFalse(SangatPushConfig.isConfigured("p", "s", "", "a", "dev.diamondspark.saraswati"))
        assertFalse(SangatPushConfig.isConfigured("p", "s", "k", "", "dev.diamondspark.saraswati"))
    }
}

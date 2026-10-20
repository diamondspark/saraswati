/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 review fix (M11) — the device id is read from its plain slot, so the Sangat poll's
// self-host filter does not open the Keystore every 30 seconds.
package com.metrolist.music.saraswati.family

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubIdentityTest {
    @Test
    fun `the device id comes without opening the sealed token`() =
        runBlocking {
            val store = FakeStore().apply { session = FamilySession("device-7", "tok") }
            val identity = FamilyStoreHubIdentity(store)
            store.failReads = java.io.IOException("keystore unavailable")

            assertEquals("device-7", identity.deviceId())
            assertNull("the token itself still needs the Keystore", identity.sessionToken())
        }

    @Test
    fun `no session means no device id`() =
        runBlocking {
            assertNull(FamilyStoreHubIdentity(FakeStore()).deviceId())
        }
}

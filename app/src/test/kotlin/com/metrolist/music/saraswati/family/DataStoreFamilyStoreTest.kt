/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — DataStoreFamilyStore against a real file-backed
// DataStore and a software vault key. The point of these tests is the part a
// fake cannot show: every secret lands sealed (and survives the startup
// scrubber, which wipes a plain write), and the claim/session bookkeeping
// holds together across the steps of pairing.
package com.metrolist.music.saraswati.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.FamilyAdminKeyKey
import com.metrolist.music.constants.FamilyDeviceIdKey
import com.metrolist.music.constants.FamilyHubClaimSecretKey
import com.metrolist.music.constants.FamilyHubSessionTokenKey
import com.metrolist.music.constants.IsFamilyOwnerKey
import com.metrolist.music.saraswati.security.CookieVault
import com.metrolist.music.saraswati.security.SecretScrubber
import com.metrolist.music.saraswati.security.SoftwareKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreFamilyStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val vault = CookieVault(SoftwareKeyProvider())
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreFamilyStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        store = DataStoreFamilyStore(dataStore, vault)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun raw(): Preferences = runBlocking { dataStore.data.first() }

    @Test
    fun `an empty phone has no admin key, session or claim`() =
        runBlocking {
            assertNull(store.adminKey())
            assertNull(store.session())
            assertNull(store.pendingClaim())
        }

    @Test
    fun `the admin key is kept sealed, marks the owner, and survives the startup scrub`() =
        runBlocking {
            store.saveOwner("the-admin-key")

            assertEquals("the-admin-key", store.adminKey())
            assertEquals(true, raw()[IsFamilyOwnerKey])
            assertNotEquals("the-admin-key", raw()[FamilyAdminKeyKey])
            assertFalse(raw()[FamilyAdminKeyKey]!!.contains("the-admin-key"))

            SecretScrubber.scrub(dataStore, vault)

            assertEquals("the-admin-key", store.adminKey())
            assertEquals(true, raw()[IsFamilyOwnerKey])
        }

    @Test
    fun `a claim is kept sealed, with the device id plain, and survives the scrub`() =
        runBlocking {
            store.savePendingClaim(PendingClaim("device-1", "the-claim-secret"))

            assertEquals(PendingClaim("device-1", "the-claim-secret"), store.pendingClaim())
            assertEquals("device-1", raw()[FamilyDeviceIdKey])
            assertFalse(raw()[FamilyHubClaimSecretKey]!!.contains("the-claim-secret"))

            SecretScrubber.scrub(dataStore, vault)

            assertEquals(PendingClaim("device-1", "the-claim-secret"), store.pendingClaim())
        }

    @Test
    fun `completing a claim keeps the session sealed and drops the claim secret`() =
        runBlocking {
            store.savePendingClaim(PendingClaim("device-1", "the-claim-secret"))

            store.completeClaim("device-1", "the-session-token")

            assertEquals(FamilySession("device-1", "the-session-token"), store.session())
            assertNull(store.pendingClaim())
            assertNull(raw()[FamilyHubClaimSecretKey])
            assertFalse(raw()[FamilyHubSessionTokenKey]!!.contains("the-session-token"))

            SecretScrubber.scrub(dataStore, vault)

            assertEquals(FamilySession("device-1", "the-session-token"), store.session())
        }

    @Test
    fun `clearing a claim forgets the device id only while there is no session`() =
        runBlocking {
            store.savePendingClaim(PendingClaim("device-1", "secret"))
            store.clearPendingClaim()
            assertNull(store.pendingClaim())
            assertNull(raw()[FamilyDeviceIdKey])

            store.completeClaim("device-2", "token")
            store.clearPendingClaim()
            assertEquals(FamilySession("device-2", "token"), store.session())
        }

    @Test
    fun `a new claim replaces an old session`() =
        runBlocking {
            store.completeClaim("device-1", "old-token")

            store.savePendingClaim(PendingClaim("device-2", "new-secret"))

            assertNull(store.session())
            assertEquals(PendingClaim("device-2", "new-secret"), store.pendingClaim())
        }

    @Test
    fun `a session or claim without its device id is not a session or claim`() =
        runBlocking {
            store.completeClaim("device-1", "token")
            dataStore.updateData { it.toMutablePreferences().apply { remove(FamilyDeviceIdKey) } }

            assertNull(store.session())
            assertNull(store.pendingClaim())
            assertTrue(raw()[FamilyHubSessionTokenKey] != null)
        }
}

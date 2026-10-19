/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave A (I4) — Keystore work (seal, open) must never run on the
// thread that calls the store or collects the sign-in flow, which in the app is
// Main. The injected dispatcher is a single thread named "fake-io", the caller
// is the test thread, and the key provider records where every crypto call ran.
// A store that did the work on the caller would show up as a "Test worker" call.
package com.metrolist.music.saraswati.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.saraswati.saral.DataStoreYouTubeSignIn
import com.metrolist.music.saraswati.security.AesGcmKeyProvider
import com.metrolist.music.saraswati.security.CookieVault
import com.metrolist.music.saraswati.security.SealedPreferences
import com.metrolist.music.saraswati.security.SoftwareKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.SecretKey

class KeystoreOffMainTest {
    @get:Rule val folder = TemporaryFolder()

    private val callThreads = CopyOnWriteArrayList<String>()
    private val software = SoftwareKeyProvider()
    private val vault =
        CookieVault(
            object : AesGcmKeyProvider {
                override fun key(): SecretKey {
                    // The coroutine debug agent appends " @coroutine#N" to the name of a thread running one.
                    callThreads.add(Thread.currentThread().name.substringBefore(" @"))
                    return software.key()
                }
            },
        )
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }
    private val fakeIo = executor.asCoroutineDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
        fakeIo.close()
    }

    private fun assertAllOffTheCaller() {
        assertTrue("the Keystore was never used", callThreads.isNotEmpty())
        assertEquals("every crypto call must run on the IO dispatcher", setOf("fake-io"), callThreads.toSet())
    }

    @Test
    fun `family store writes and unseals run on the IO dispatcher`() =
        runBlocking {
            val store = DataStoreFamilyStore(dataStore, vault, fakeIo)

            store.saveOwner("the-admin-key")
            assertEquals("the-admin-key", store.adminKey())
            store.savePendingClaim(PendingClaim("device-2", "the-secret"))
            assertEquals(PendingClaim("device-2", "the-secret"), store.pendingClaim())
            store.completeClaim("device-2", "the-session")
            assertEquals(FamilySession("device-2", "the-session"), store.session())
            store.clearPendingClaim()
            store.clearSession("device-2")
            store.markStationsSaved()
            assertTrue(store.stationsEverSaved())

            assertAllOffTheCaller()
        }

    @Test
    fun `family store reads of a value sealed elsewhere also run on the IO dispatcher`() =
        runBlocking {
            val store = DataStoreFamilyStore(dataStore, vault, fakeIo)
            store.saveOwner("the-admin-key")
            callThreads.clear()

            repeat(3) { store.adminKey() }

            assertEquals(listOf("fake-io", "fake-io", "fake-io"), callThreads.toList())
        }

    @Test
    fun `sign in state is decrypted on the IO dispatcher`() =
        runBlocking {
            dataStore.edit { SealedPreferences.write(it, InnerTubeCookieKey, "SAPISID=abc", vault) }
            callThreads.clear()

            val signedIn = withTimeout(5_000) { DataStoreYouTubeSignIn(dataStore, vault, fakeIo).signedIn.first() }

            assertTrue(signedIn)
            assertAllOffTheCaller()
        }

    @Test
    fun `a change to an unrelated key does not decrypt the cookie again`() =
        runBlocking {
            val signIn = DataStoreYouTubeSignIn(dataStore, vault, fakeIo)
            val seen = async { withTimeout(10_000) { signIn.signedIn.take(3).toList() } }
            delay(150)
            dataStore.edit { SealedPreferences.write(it, InnerTubeCookieKey, "SAPISID=abc", vault) }
            delay(150)
            callThreads.clear()

            dataStore.edit { it[PureBlackKey] = true }
            delay(150)
            dataStore.edit { it[PureBlackKey] = false }
            delay(150)
            assertFalse("an unrelated edit must not touch the Keystore", callThreads.isNotEmpty())

            dataStore.edit { it.remove(InnerTubeCookieKey) }
            assertEquals(listOf(false, true, false), seen.await())
        }
}

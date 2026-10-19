/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — Saral shows its sign-in tile from the same
// test the rest of the app uses for "signed in to YouTube": the sealed sign-in
// cookie, opened with the vault, holds a SAPISID. Nothing here is a second
// notion of being signed in.
package com.metrolist.music.saraswati.saral

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.saraswati.security.CookieVault
import com.metrolist.music.saraswati.security.SealedPreferences
import com.metrolist.music.saraswati.security.SoftwareKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

class DataStoreYouTubeSignInTest {
    @get:Rule val folder = TemporaryFolder()

    private val vault = CookieVault(SoftwareKeyProvider())
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var signIn: DataStoreYouTubeSignIn

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        signIn = DataStoreYouTubeSignIn(dataStore, vault)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun current(): Boolean = runBlocking { withTimeout(5_000) { signIn.signedIn.first() } }

    private fun saveCookie(cookie: String) = runBlocking { dataStore.edit { SealedPreferences.write(it, InnerTubeCookieKey, cookie, vault) } }

    @Test
    fun `a phone with no cookie is signed out`() {
        assertFalse(current())
    }

    @Test
    fun `a sealed cookie holding SAPISID is signed in`() {
        saveCookie("HSID=abc; SAPISID=def/ghi; SID=jkl")

        assertTrue(current())
    }

    @Test
    fun `a cookie without SAPISID is signed out`() {
        saveCookie("HSID=abc; SID=jkl")

        assertFalse(current())
    }

    @Test
    fun `a plain, unsealed cookie value is not trusted`() {
        runBlocking { dataStore.edit { it[InnerTubeCookieKey] = "SAPISID=def" } }

        assertFalse(current())
    }

    @Test
    fun `the answer follows signing in and signing out`() =
        runBlocking {
            val seen = async { withTimeout(5_000) { signIn.signedIn.take(3).toList() } }
            delay(100)
            saveCookie("SAPISID=def")
            delay(100)
            dataStore.edit { it.remove(InnerTubeCookieKey) }

            assertEquals(listOf(false, true, false), seen.await())
        }

    @Test
    fun `the check is the app's own SAPISID test`() {
        assertTrue(isYouTubeSignedIn("SAPISID=x"))
        assertTrue(isYouTubeSignedIn("a=b; SAPISID=x; c=d"))
        assertFalse(isYouTubeSignedIn("SAPISID"))
        assertFalse(isYouTubeSignedIn("XSAPISID=x"))
        assertFalse(isYouTubeSignedIn(""))
        assertFalse(isYouTubeSignedIn(null))
    }
}

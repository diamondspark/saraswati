/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B2 — App.onCreate sets SaralActive from the cached role so a
// startup or service crash on a Saral phone already shows the Saral crash screen.
// The rule under test: only a stored `true` turns it on, and no failure of the
// read ever does.
package com.metrolist.music.saraswati.saral

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.GrandparentRoleKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SaralActiveInitTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        SaralActive.on = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        SaralActive.on = false
        scope.cancel()
    }

    private fun failingStore(failure: Throwable): DataStore<Preferences> =
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = error("not used")
        }

    @Test
    fun `a cached grandparent role turns the flag on at startup`() {
        runBlocking { dataStore.edit { it[GrandparentRoleKey] = true } }

        runBlocking { SaralActive.initFrom(dataStore) }

        assertTrue(SaralActive.on)
    }

    @Test
    fun `a phone that never had a role leaves the flag off`() {
        runBlocking { SaralActive.initFrom(dataStore) }

        assertFalse(SaralActive.on)
    }

    @Test
    fun `a cached adult role turns it off`() {
        SaralActive.on = true
        runBlocking { dataStore.edit { it[GrandparentRoleKey] = false } }

        runBlocking { SaralActive.initFrom(dataStore) }

        assertFalse(SaralActive.on)
    }

    @Test
    fun `a read that fails never turns the flag on and never throws`() {
        for (failure in listOf<Throwable>(IOException("disk"), IllegalStateException("corrupt"), java.io.EOFException())) {
            SaralActive.on = false

            runBlocking { SaralActive.initFrom(failingStore(failure)) }

            assertFalse(failure.toString(), SaralActive.on)
        }
    }

    @Test
    fun `a read that fails leaves a flag that was already set as it was`() {
        SaralActive.on = true

        runBlocking { SaralActive.initFrom(failingStore(IOException("disk"))) }

        assertTrue(SaralActive.on)
    }

    @Test
    fun `cancelling the read is not swallowed`() {
        val cancelled = kotlin.coroutines.cancellation.CancellationException("stop")

        val thrown = runCatching { runBlocking { SaralActive.initFrom(failingStore(cancelled)) } }.exceptionOrNull()

        assertTrue(thrown is kotlin.coroutines.cancellation.CancellationException)
        assertFalse(SaralActive.on)
    }
}

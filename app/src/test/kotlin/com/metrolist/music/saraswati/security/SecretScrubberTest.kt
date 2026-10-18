/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the startup scrubber (Review Focus #4 —
// a restored Metrolist backup, or any legacy file, must not leave a
// plaintext cookie or integration token in settings).
package com.metrolist.music.saraswati.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMSessionKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.ProxyPasswordKey
import com.metrolist.music.constants.PureBlackKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Minimal in-memory [DataStore] so these tests need no disk I/O or Android runtime. */
private class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

class SecretScrubberTest {
    private val vault = CookieVault(SoftwareKeyProvider())

    @Test
    fun `drops a plaintext cookie`() =
        runBlocking {
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(InnerTubeCookieKey to "SAPISID=abc; HSID=def456"),
                )

            SecretScrubber.scrub(dataStore, vault)

            assertNull(dataStore.data.first()[InnerTubeCookieKey])
        }

    @Test
    fun `drops integration tokens`() =
        runBlocking {
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(
                        OpenRouterApiKey to "sk-or-abc123",
                        DeeplApiKey to "deepl-xyz789",
                        LastFMSessionKey to "lastfm-session-token",
                        ProxyPasswordKey to "hunter2",
                    ),
                )

            SecretScrubber.scrub(dataStore, vault)

            val after = dataStore.data.first()
            assertNull(after[OpenRouterApiKey])
            assertNull(after[DeeplApiKey])
            assertNull(after[LastFMSessionKey])
            assertNull(after[ProxyPasswordKey])
        }

    @Test
    fun `keeps a vault-sealed cookie`() =
        runBlocking {
            val sealed = vault.seal("SAPISID=abc; HSID=def456")
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(InnerTubeCookieKey to sealed),
                )

            SecretScrubber.scrub(dataStore, vault)

            assertEquals(sealed, dataStore.data.first()[InnerTubeCookieKey])
        }

    @Test
    fun `keeps unrelated preferences`() =
        runBlocking {
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(PureBlackKey to true),
                )

            SecretScrubber.scrub(dataStore, vault)

            assertEquals(true, dataStore.data.first()[PureBlackKey])
        }
}

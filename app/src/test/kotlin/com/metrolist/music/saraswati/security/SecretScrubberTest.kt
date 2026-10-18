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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.ProviderException
import javax.crypto.SecretKey

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

    // SARASWATI (fix round 1): a broken/custom-ROM Keystore implementation
    // can make AesGcmKeyProvider.key() throw a java.security.ProviderException
    // — a RuntimeException, not a GeneralSecurityException — which
    // CookieVault.open()'s catch blocks do not cover, so it propagates
    // straight out of scrub(). That is real and expected; the fix is that
    // App.onCreate now wraps its SecretScrubber.scrub(...) call in a plain
    // try/catch (Exception) so a broken Keystore degrades to "skip this
    // boot's scrub" instead of crashing every single app launch. This test
    // pins both halves: scrub() really does throw with a broken provider,
    // and App.onCreate's catch pattern really does stop it from escaping.
    @Test
    fun `a broken keystore during scrub does not escape App onCreate's guard`() =
        runBlocking {
            val brokenVault =
                CookieVault(
                    object : AesGcmKeyProvider {
                        override fun key(): SecretKey = throw ProviderException("Keystore unavailable")
                    },
                )
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(InnerTubeCookieKey to vault.seal("SAPISID=abc; HSID=def456")),
                )

            var caught: Throwable? = null
            try {
                SecretScrubber.scrub(dataStore, brokenVault)
            } catch (e: Exception) {
                caught = e
            }

            assertTrue(
                "expected scrub() to throw a ProviderException with a broken Keystore " +
                    "(that's the real gap App.onCreate's try/catch now guards against) " +
                    "but got: $caught",
                caught is ProviderException,
            )
        }
}

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
import com.metrolist.music.constants.FamilyAdminKeyKey
import com.metrolist.music.constants.FamilyHubClaimSecretKey
import com.metrolist.music.constants.FamilyHubSessionTokenKey
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

    private val hubKeys = listOf(FamilyAdminKeyKey, FamilyHubSessionTokenKey, FamilyHubClaimSecretKey)

    // SARASWATI: Task 6 — the hub credentials are in SecretPreferenceKeys.all
    // (so a backup never carries them) but are also kept sealed at rest, so
    // the scrubber must keep the sealed ones exactly as it keeps the cookie.
    // Without this the owner would be signed out of the hub on every start.
    @Test
    fun `keeps vault-sealed hub credentials`() =
        runBlocking {
            val sealed = hubKeys.associateWith { SealedPreferences.seal(it, "value-for-${it.name}", vault) }
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(*sealed.map { (key, value) -> key to value }.toTypedArray()),
                )

            SecretScrubber.scrub(dataStore, vault)

            val after = dataStore.data.first()
            for ((key, value) in sealed) {
                assertEquals(key.name, value, after[key])
            }
        }

    @Test
    fun `drops a value sealed for a different slot, hub or cookie`() =
        runBlocking {
            val slots = hubKeys + InnerTubeCookieKey
            for (from in slots) {
                for (into in slots) {
                    if (from == into) continue
                    val dataStore =
                        InMemoryPreferencesDataStore(
                            mutablePreferencesOf(into to SealedPreferences.seal(from, "from-${from.name}", vault)),
                        )

                    SecretScrubber.scrub(dataStore, vault)

                    assertNull("${from.name} replayed into ${into.name}", dataStore.data.first()[into])
                }
            }
        }

    @Test
    fun `drops plaintext hub credentials`() =
        runBlocking {
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(*hubKeys.map { it to "plaintext-${it.name}" }.toTypedArray()),
                )

            SecretScrubber.scrub(dataStore, vault)

            val after = dataStore.data.first()
            for (key in hubKeys) {
                assertNull(key.name, after[key])
            }
        }

    @Test
    fun `drops hub credentials sealed by a different device's key`() =
        runBlocking {
            val otherDevice = CookieVault(SoftwareKeyProvider())
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(*hubKeys.map { it to otherDevice.seal("from-another-phone") }.toTypedArray()),
                )

            SecretScrubber.scrub(dataStore, vault)

            val after = dataStore.data.first()
            for (key in hubKeys) {
                assertNull(key.name, after[key])
            }
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

    // SARASWATI (fix round 1; superseded by I5): a broken/custom-ROM
    // Keystore implementation can make AesGcmKeyProvider.key() throw a
    // java.security.ProviderException — a RuntimeException. This test used
    // to pin that CookieVault.open()'s catch blocks did NOT cover that, so
    // it escaped straight out of scrub(), relying on App.onCreate's own
    // try/catch as the only thing standing between a broken Keystore and a
    // crash on every single launch. I5 closed that gap one layer down
    // instead: CookieVault.open() now also catches
    // RuntimeException/ProviderException and returns null, matching its
    // documented "never throws" contract — so scrub() no longer throws at
    // all with a broken provider. The degraded cookie is scrubbed like any
    // other value open() can't verify (the safe default — see
    // SecretScrubber.kt's own "wiped, not repaired" doc comment).
    // App.onCreate's try/catch around scrub() in App.kt remains as
    // defense-in-depth for anything else scrub() could still throw (e.g. a
    // DataStore IO error), but a broken Keystore specifically no longer
    // needs it.
    @Test
    fun `a broken keystore during scrub does not throw and scrubs the unverifiable cookie`() =
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

            // Must not throw — this is the call App.onCreate makes
            // unguarded in spirit, even though App.kt itself still wraps it
            // in a try/catch for defense-in-depth.
            SecretScrubber.scrub(dataStore, brokenVault)

            assertNull(
                "a cookie that can't be verified through a broken Keystore must be scrubbed, not kept",
                dataStore.data.first()[InnerTubeCookieKey],
            )
        }
}

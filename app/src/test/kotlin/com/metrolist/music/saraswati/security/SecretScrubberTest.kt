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

    // SARASWATI (fix wave A, I1): a Keystore that merely throws (a hiccup at
    // startup, a broken custom-ROM provider) says nothing about the stored
    // values. They are KEPT — wiping the hub credentials here left a
    // grandparent's phone with no session and no way to ask for one — and the
    // next start tries again. Only a value the vault REJECTS is wiped.
    private fun throwingVault(failure: Throwable = ProviderException("Keystore unavailable")) =
        CookieVault(
            object : AesGcmKeyProvider {
                override fun key(): SecretKey = throw failure
            },
        )

    @Test
    fun `hub credentials and the cookie survive a scrub while the Keystore is unavailable`() =
        runBlocking {
            val sealed = (hubKeys + InnerTubeCookieKey).associateWith { SealedPreferences.seal(it, "value-for-${it.name}", vault) }
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(*sealed.map { (key, value) -> key to value }.toTypedArray()),
                )

            // Must not throw — this runs on every start.
            SecretScrubber.scrub(dataStore, throwingVault())

            val after = dataStore.data.first()
            for ((key, value) in sealed) {
                assertEquals("${key.name} must be kept", value, after[key])
            }
        }

    @Test
    fun `each kind of Keystore failure keeps the sealed value`() =
        runBlocking {
            val failures =
                listOf(
                    ProviderException("keystore.db corrupted"),
                    java.security.KeyStoreException("not ready"),
                    java.security.UnrecoverableKeyException("busy"),
                    IllegalStateException("provider crashed"),
                )
            for (failure in failures) {
                val sealed = SealedPreferences.seal(FamilyHubSessionTokenKey, "the-session", vault)
                val dataStore = InMemoryPreferencesDataStore(mutablePreferencesOf(FamilyHubSessionTokenKey to sealed))

                SecretScrubber.scrub(dataStore, throwingVault(failure))

                assertEquals(failure.toString(), sealed, dataStore.data.first()[FamilyHubSessionTokenKey])
            }
        }

    @Test
    fun `a kept value is opened again once the Keystore recovers, and a bad one is then dropped`() =
        runBlocking {
            val good = SealedPreferences.seal(FamilyHubSessionTokenKey, "the-session", vault)
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(
                        FamilyHubSessionTokenKey to good,
                        FamilyAdminKeyKey to CookieVault(SoftwareKeyProvider()).seal("other-phone", "familyAdminKey"),
                    ),
                )

            SecretScrubber.scrub(dataStore, throwingVault())
            val whileDown = dataStore.data.first()
            assertEquals(good, whileDown[FamilyHubSessionTokenKey])
            assertEquals("not judged while the Keystore is down", true, whileDown[FamilyAdminKeyKey] != null)

            SecretScrubber.scrub(dataStore, vault)
            val afterRecovery = dataStore.data.first()
            assertEquals(good, afterRecovery[FamilyHubSessionTokenKey])
            assertNull("rejected once the Keystore answers", afterRecovery[FamilyAdminKeyKey])
        }

    @Test
    fun `plaintext is wiped even while the Keystore is unavailable`() =
        runBlocking {
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(
                        InnerTubeCookieKey to "SAPISID=abc; HSID=def456",
                        FamilyAdminKeyKey to "plaintext-admin",
                        OpenRouterApiKey to "sk-or-abc123",
                    ),
                )

            SecretScrubber.scrub(dataStore, throwingVault())

            val after = dataStore.data.first()
            assertNull(after[InnerTubeCookieKey])
            assertNull(after[FamilyAdminKeyKey])
            assertNull(after[OpenRouterApiKey])
        }

    @Test
    fun `a tampered tag is wiped`() =
        runBlocking {
            val sealed = SealedPreferences.seal(FamilyHubSessionTokenKey, "the-session", vault)
            val bytes = java.util.Base64.getDecoder().decode(sealed.removePrefix("v1:"))
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
            val tampered = "v1:" + java.util.Base64.getEncoder().encodeToString(bytes)
            val dataStore = InMemoryPreferencesDataStore(mutablePreferencesOf(FamilyHubSessionTokenKey to tampered))

            SecretScrubber.scrub(dataStore, vault)

            assertNull(dataStore.data.first()[FamilyHubSessionTokenKey])
        }

    @Test
    fun `a foreign blob that only looks sealed is wiped`() =
        runBlocking {
            val blobs =
                listOf(
                    "v1:",
                    "v1:!!!not base64!!!",
                    "v1:" + java.util.Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() }),
                    "v1:" + java.util.Base64.getEncoder().encodeToString(ByteArray(5)),
                )
            for (blob in blobs) {
                val dataStore =
                    InMemoryPreferencesDataStore(
                        mutablePreferencesOf(FamilyHubSessionTokenKey to blob, InnerTubeCookieKey to blob),
                    )

                SecretScrubber.scrub(dataStore, vault)

                val after = dataStore.data.first()
                assertNull(blob, after[FamilyHubSessionTokenKey])
                assertNull(blob, after[InnerTubeCookieKey])
            }
        }

    // The two phases (I4): the plaintext sweep needs no Keystore at all, and the
    // sealed check leaves a value that changed while it was running.
    @Test
    fun `the plaintext sweep keeps anything in the vault's format and needs no vault`() =
        runBlocking {
            val sealed = SealedPreferences.seal(FamilyHubSessionTokenKey, "the-session", vault)
            val dataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(
                        FamilyHubSessionTokenKey to sealed,
                        FamilyAdminKeyKey to "plaintext-admin",
                        OpenRouterApiKey to "sk-or-abc123",
                        PureBlackKey to true,
                    ),
                )

            SecretScrubber.scrubPlaintext(dataStore)

            val after = dataStore.data.first()
            assertEquals(sealed, after[FamilyHubSessionTokenKey])
            assertNull(after[FamilyAdminKeyKey])
            assertNull(after[OpenRouterApiKey])
            assertEquals(true, after[PureBlackKey])
        }

    @Test
    fun `the sealed check does not wipe a value written while it ran`() =
        runBlocking {
            val keys = SoftwareKeyProvider()
            val stale = CookieVault(SoftwareKeyProvider()).seal("old-phone", "familyHubSessionToken")
            val fresh = SealedPreferences.seal(FamilyHubSessionTokenKey, "new-session", CookieVault(keys))
            val dataStore = InMemoryPreferencesDataStore(mutablePreferencesOf(FamilyHubSessionTokenKey to stale))
            val racingVault =
                CookieVault(
                    object : AesGcmKeyProvider {
                        override fun key(): SecretKey {
                            // A sign-in lands between the snapshot and the wipe.
                            runBlocking { dataStore.updateData { it.toMutablePreferences().apply { set(FamilyHubSessionTokenKey, fresh) } } }
                            return keys.key()
                        }
                    },
                )

            SecretScrubber.scrubSealed(dataStore, racingVault)

            assertEquals(fresh, dataStore.data.first()[FamilyHubSessionTokenKey])
        }
}

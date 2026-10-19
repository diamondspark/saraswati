/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B2 — AndroidKeystoreKeyProvider decides "generate" from
// KeyStore.getKey alone (null means there is no key), no longer from
// containsAlias. That is only safe if, on a real Android Keystore, getKey is null
// exactly while the alias is empty and containsAlias agrees with getKey once a key
// has been generated. This runs against the real AndroidKeyStore, on its own alias,
// so it never touches the key that seals the app's cookie and hub credentials.
// It needs a device or emulator; it compiles on a machine without one.
package com.metrolist.music.saraswati.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreKeyProviderInstrumentedTest {
    private val alias = "saraswati_instrumented_test_key"
    private lateinit var keyStore: KeyStore

    @Before
    fun setUp() {
        keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(alias)
    }

    @After
    fun tearDown() {
        keyStore.deleteEntry(alias)
    }

    @Test
    fun getKeyIsNullAndContainsAliasFalseBeforeAnyKeyIsGenerated() {
        assertNull(keyStore.getKey(alias, null))
        assertFalse(keyStore.containsAlias(alias))
    }

    @Test
    fun containsAliasAgreesWithGetKeyAfterTheProviderGeneratesTheKey() {
        val provider = AndroidKeystoreKeyProvider(alias)

        provider.key()

        assertNotNull("getKey finds the generated key", keyStore.getKey(alias, null))
        assertTrue("containsAlias agrees with getKey", keyStore.containsAlias(alias))
    }

    @Test
    fun aSecondProviderOnTheSameAliasLoadsTheKeyInsteadOfReplacingIt() {
        val vault = CookieVault(AndroidKeystoreKeyProvider(alias))
        val sealed = vault.seal("SAPISID=abc123")

        val opened = CookieVault(AndroidKeystoreKeyProvider(alias)).open(sealed)

        assertEquals("a fresh provider must have loaded the key the first one generated", OpenResult.Opened("SAPISID=abc123"), opened)
    }

    @Test
    fun deletingTheKeyMakesTheNextCallGenerateAFreshOne() {
        val provider = AndroidKeystoreKeyProvider(alias)
        val sealed = CookieVault(provider).seal("old")
        keyStore.deleteEntry(alias)

        assertNull(keyStore.getKey(alias, null))
        val reopened = CookieVault(AndroidKeystoreKeyProvider(alias)).open(sealed)

        assertEquals("the old value is sealed under a key that no longer exists", OpenResult.Rejected, reopened)
        assertNotNull(keyStore.getKey(alias, null))
    }
}

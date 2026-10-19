/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave A (I1) — the key provider generates a key only when the
// alias is genuinely absent. Regenerating over an alias that exists but could
// not be loaded just now would make every sealed value permanently unreadable.
package com.metrolist.music.saraswati.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyStoreException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class AndroidKeystoreKeyProviderTest {
    private val aKey: SecretKey = SecretKeySpec(ByteArray(32), "AES")

    private class FakeKeyStore(
        var contains: () -> Boolean,
        var load: () -> SecretKey?,
        var generated: SecretKey,
    ) : KeyStoreAccess {
        var generateCalls = 0

        override fun containsAlias(): Boolean = contains()

        override fun load(): SecretKey? = load.invoke()

        override fun generate(): SecretKey {
            generateCalls++
            return generated
        }
    }

    private fun provider(store: KeyStoreAccess) = AndroidKeystoreKeyProvider("alias", store)

    @Test
    fun `an existing key is loaded and nothing is generated`() {
        val store = FakeKeyStore({ true }, { aKey }, SecretKeySpec(ByteArray(32) { 1 }, "AES"))

        assertSame(aKey, provider(store).key())
        assertEquals(0, store.generateCalls)
    }

    @Test
    fun `a missing alias generates exactly one key`() {
        val fresh = SecretKeySpec(ByteArray(32) { 2 }, "AES")
        val store = FakeKeyStore({ false }, { error("must not load an absent alias") }, fresh)

        assertSame(fresh, provider(store).key())
        assertEquals(1, store.generateCalls)
    }

    @Test
    fun `an alias that exists but cannot be loaded is an error, never a new key`() {
        val store = FakeKeyStore({ true }, { null }, aKey)

        try {
            provider(store).key()
            fail("expected the provider to refuse")
        } catch (e: KeyStoreException) {
            assertTrue(e.message!!.contains("could not be loaded"))
        }
        assertEquals("regenerating would orphan every sealed value", 0, store.generateCalls)
    }

    @Test
    fun `a Keystore that throws while looking for the alias never generates`() {
        val store = FakeKeyStore({ throw KeyStoreException("not ready") }, { aKey }, aKey)

        try {
            provider(store).key()
            fail("expected the failure to propagate")
        } catch (e: KeyStoreException) {
            assertEquals("not ready", e.message)
        }
        assertEquals(0, store.generateCalls)
    }

    @Test
    fun `a Keystore that throws while loading never generates`() {
        val store = FakeKeyStore({ true }, { throw java.security.UnrecoverableKeyException("busy") }, aKey)

        try {
            provider(store).key()
            fail("expected the failure to propagate")
        } catch (e: java.security.UnrecoverableKeyException) {
            // expected
        }
        assertEquals(0, store.generateCalls)
    }

    @Test
    fun `a transient failure heals on the next call without having generated`() {
        var failing = true
        val store = FakeKeyStore({ if (failing) throw KeyStoreException("hiccup") else true }, { aKey }, aKey)
        val provider = provider(store)

        runCatching { provider.key() }
        failing = false

        assertSame(aKey, provider.key())
        assertEquals(0, store.generateCalls)
    }
}

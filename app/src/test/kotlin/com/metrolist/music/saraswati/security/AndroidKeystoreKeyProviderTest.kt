/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave A (I1), wave B2 — the key provider generates a key only when
// the Keystore reports, without an error, that nothing is stored under the alias
// (KeyStore.getKey returns null). Generating over a key that exists but could not
// be loaded just now would make every sealed value permanently unreadable, and
// containsAlias is not trusted for this: it can swallow a transient error and
// answer false for a key that is there.
package com.metrolist.music.saraswati.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class AndroidKeystoreKeyProviderTest {
    private val aKey: SecretKey = SecretKeySpec(ByteArray(32), "AES")

    /** A Keystore with one slot: [load] reads it, [generate] fills it, as the real one does. */
    private class FakeKeyStore(
        var stored: SecretKey? = null,
        var load: (SecretKey?) -> SecretKey? = { it },
        var generated: SecretKey = SecretKeySpec(ByteArray(32) { 9 }, "AES"),
        var generateDelayMs: Long = 0,
    ) : KeyStoreAccess {
        @Volatile var generateCalls = 0

        override fun load(): SecretKey? = load.invoke(stored)

        override fun generate(): SecretKey {
            generateCalls++
            if (generateDelayMs > 0) Thread.sleep(generateDelayMs)
            stored = generated
            return generated
        }
    }

    private fun provider(store: KeyStoreAccess) = AndroidKeystoreKeyProvider(store)

    @Test
    fun `an existing key is loaded and nothing is generated`() {
        val store = FakeKeyStore(stored = aKey)

        assertSame(aKey, provider(store).key())
        assertEquals(0, store.generateCalls)
    }

    @Test
    fun `an alias with no key generates exactly one key, and the next call loads it`() {
        val fresh = SecretKeySpec(ByteArray(32) { 2 }, "AES")
        val store = FakeKeyStore(generated = fresh)
        val provider = provider(store)

        assertSame(fresh, provider.key())
        assertSame(fresh, provider.key())

        assertEquals(1, store.generateCalls)
    }

    @Test
    fun `a Keystore that throws while loading never generates, whatever it throws`() {
        for (failure in listOf<Exception>(KeyStoreException("not ready"), UnrecoverableKeyException("busy"), java.security.ProviderException("died"), IllegalStateException("odd"))) {
            val store = FakeKeyStore(stored = aKey, load = { throw failure })

            try {
                provider(store).key()
                fail("expected the failure to propagate: $failure")
            } catch (e: Exception) {
                assertSame(failure, e)
            }
            assertEquals("regenerating would orphan every sealed value: $failure", 0, store.generateCalls)
            assertSame("the stored key is untouched: $failure", aKey, store.stored)
        }
    }

    @Test
    fun `a transient failure heals on the next call without having generated`() {
        var failing = true
        val store = FakeKeyStore(stored = aKey, load = { if (failing) throw KeyStoreException("hiccup") else it })
        val provider = provider(store)

        runCatching { provider.key() }
        failing = false

        assertSame(aKey, provider.key())
        assertEquals(0, store.generateCalls)
    }

    @Test
    fun `a failure in generating is the caller's, and the next call generates again`() {
        var failures = 1
        val store = FakeKeyStore()
        val failingOnce =
            object : KeyStoreAccess {
                override fun load() = store.load()

                override fun generate(): SecretKey {
                    if (failures-- > 0) throw KeyStoreException("no space")
                    return store.generate()
                }
            }
        val provider = provider(failingOnce)

        try {
            provider.key()
            fail("expected the failure")
        } catch (e: KeyStoreException) {
            assertTrue(e.message!!.contains("no space"))
        }

        assertSame(store.generated, provider.key())
        assertEquals(1, store.generateCalls)
    }

    @Test
    fun `two threads on a fresh Keystore generate one key between them`() {
        val store = FakeKeyStore(generateDelayMs = 150)
        val provider = provider(store)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val keys = List(2) { pool.submit<SecretKey> { barrier.await(); provider.key() } }.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals("a second generate would overwrite the first key", 1, store.generateCalls)
            assertSame(keys[0], keys[1])
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `key is synchronized`() {
        assertTrue(java.lang.reflect.Modifier.isSynchronized(AndroidKeystoreKeyProvider::class.java.getDeclaredMethod("key").modifiers))
    }
}

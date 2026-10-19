/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: CookieVault is the only thing standing between the YouTube
// sign-in cookie and plaintext-on-disk, so every one of its failure modes
// (tampering, a foreign key, legacy plaintext) must return null rather
// than silently producing garbage a caller might treat as a real cookie.
package com.metrolist.music.saraswati.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.ProviderException
import java.util.Base64
import javax.crypto.SecretKey

class CookieVaultTest {
    private val vault = CookieVault(SoftwareKeyProvider())

    @Test
    fun `round trip returns the original`() {
        val cookie = "SAPISID=abc123; HSID=def456; __Secure-3PSID=ghi789"
        val sealed = vault.seal(cookie)
        assertEquals(cookie, vault.open(sealed))
    }

    @Test
    fun `sealed text contains no cookie fragment`() {
        val sealed = vault.seal("SAPISID=abc")
        assertFalse(sealed.contains("SAPISID=abc"))
    }

    @Test
    fun `tampered ciphertext opens to null`() {
        val sealed = vault.seal("SAPISID=abc")
        val bytes = Base64.getDecoder().decode(sealed.removePrefix("v1:"))
        // Flip a bit in the last byte (part of the GCM tag) so authentication fails.
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = "v1:" + Base64.getEncoder().encodeToString(bytes)

        assertNull(vault.open(tampered))
    }

    @Test
    fun `a different key opens to null`() {
        val sealed = vault.seal("SAPISID=abc")
        val otherVault = CookieVault(SoftwareKeyProvider())

        assertNull(otherVault.open(sealed))
    }

    @Test
    fun `unprefixed legacy plaintext opens to null`() {
        assertNull(vault.open("SAPISID=abc; HSID=def456"))
    }

    @Test
    fun `empty string round-trips`() {
        val sealed = vault.seal("")
        assertEquals("", vault.open(sealed))
    }

    @Test
    fun `open returns null instead of throwing when the Keystore is broken`() {
        // SARASWATI (I5): a broken real Keystore can throw an unchecked
        // java.security.ProviderException from cipher operations, not just
        // from key generation. open() is documented as "never throws" —
        // this proves that contract holds even for a RuntimeException.
        val sealed = vault.seal("SAPISID=abc")
        val brokenVault = CookieVault(ThrowingKeyProvider())

        assertNull(brokenVault.open(sealed))
    }

    private class ThrowingKeyProvider : AesGcmKeyProvider {
        // Models a Keystore provider that fails on every crypto operation
        // (e.g. a corrupted keystore.db) by throwing from key() itself —
        // CookieVault.open() calls keys.key() before any Cipher call, so
        // this reaches the same catch block a Cipher-level ProviderException
        // would.
        override fun key(): SecretKey {
            throw ProviderException("Keystore is broken")
        }
    }
    // SARASWATI: per-slot AAD — a ciphertext is bound to the slot it was
    // sealed for, so one slot's value can't be replayed into another's.
    @Test
    fun `a value sealed for one purpose does not open under another`() {
        val sealed = vault.seal("secret", purpose = "familyAdminKey")

        assertEquals("secret", vault.open(sealed, purpose = "familyAdminKey"))
        assertNull(vault.open(sealed, purpose = "familyHubSessionToken"))
        assertNull(vault.open(sealed))
        assertNull(vault.open(sealed, purpose = "cookie"))
    }

    @Test
    fun `a value sealed with no purpose is the cookie's and opens only as the cookie`() {
        val sealed = vault.seal("SAPISID=abc")

        assertEquals("SAPISID=abc", vault.open(sealed))
        assertEquals("SAPISID=abc", vault.open(sealed, purpose = "cookie"))
        assertNull(vault.open(sealed, purpose = "familyAdminKey"))
    }

    @Test
    fun `the cookie's AAD is unchanged so phones that already hold a sealed cookie keep it`() {
        val provider = SoftwareKeyProvider()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, provider.key())
        cipher.updateAAD("saraswati.cookie.v1".toByteArray(Charsets.UTF_8))
        val legacy = "v1:" + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal("SAPISID=old".toByteArray()))

        assertEquals("SAPISID=old", CookieVault(provider).open(legacy))
    }

    @Test
    fun `a purpose that could blur the AAD is refused`() {
        listOf("", "a.b", "a b", "a\u0000b", "../cookie").forEach { purpose ->
            assertNull(purpose, runCatching { vault.seal("x", purpose = purpose) }.getOrNull())
            assertNull(purpose, vault.open(vault.seal("x"), purpose = purpose))
        }
    }
}

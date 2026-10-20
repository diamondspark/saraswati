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
        assertEquals(OpenResult.Opened(cookie), vault.open(sealed))
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

        assertEquals(OpenResult.Rejected, vault.open(tampered))
    }

    @Test
    fun `a different key opens to null`() {
        val sealed = vault.seal("SAPISID=abc")
        val otherVault = CookieVault(SoftwareKeyProvider())

        assertEquals(OpenResult.Rejected, otherVault.open(sealed))
    }

    @Test
    fun `unprefixed legacy plaintext opens to null`() {
        assertEquals(OpenResult.Rejected, vault.open("SAPISID=abc; HSID=def456"))
    }

    @Test
    fun `empty string round-trips`() {
        val sealed = vault.seal("")
        assertEquals(OpenResult.Opened(""), vault.open(sealed))
    }

    @Test
    fun `open returns null instead of throwing when the Keystore is broken`() {
        // SARASWATI (I5): a broken real Keystore can throw an unchecked
        // java.security.ProviderException from cipher operations, not just
        // from key generation. open() is documented as "never throws" —
        // this proves that contract holds even for a RuntimeException.
        val sealed = vault.seal("SAPISID=abc")
        val brokenVault = CookieVault(ThrowingKeyProvider())

        assertEquals(OpenResult.Unavailable, brokenVault.open(sealed))
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

        assertEquals(OpenResult.Opened("secret"), vault.open(sealed, purpose = "familyAdminKey"))
        assertEquals(OpenResult.Rejected, vault.open(sealed, purpose = "familyHubSessionToken"))
        assertEquals(OpenResult.Rejected, vault.open(sealed))
        assertEquals(OpenResult.Rejected, vault.open(sealed, purpose = "cookie"))
    }

    @Test
    fun `a value sealed with no purpose is the cookie's and opens only as the cookie`() {
        val sealed = vault.seal("SAPISID=abc")

        assertEquals(OpenResult.Opened("SAPISID=abc"), vault.open(sealed))
        assertEquals(OpenResult.Opened("SAPISID=abc"), vault.open(sealed, purpose = "cookie"))
        assertEquals(OpenResult.Rejected, vault.open(sealed, purpose = "familyAdminKey"))
    }

    @Test
    fun `the cookie's AAD is unchanged so phones that already hold a sealed cookie keep it`() {
        val provider = SoftwareKeyProvider()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, provider.key())
        cipher.updateAAD("saraswati.cookie.v1".toByteArray(Charsets.UTF_8))
        val legacy = "v1:" + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal("SAPISID=old".toByteArray()))

        assertEquals(OpenResult.Opened("SAPISID=old"), CookieVault(provider).open(legacy))
    }

    @Test
    fun `a purpose that could blur the AAD is refused`() {
        listOf("", "a.b", "a b", "a\u0000b", "../cookie").forEach { purpose ->
            assertNull(purpose, runCatching { vault.seal("x", purpose = purpose) }.getOrNull())
            assertEquals(purpose, OpenResult.Rejected, vault.open(vault.seal("x"), purpose = purpose))
        }
    }

    // SARASWATI: fix wave A (I1) — the three answers open() gives, and the one
    // thing each licenses a caller to do.
    @Test
    fun `malformed or foreign blobs are rejected`() {
        val tooShort = "v1:" + Base64.getEncoder().encodeToString(ByteArray(27))
        listOf(
            "v1:" to "an empty payload",
            "v1:!!!not base64!!!" to "bad Base64",
            "v1:" + Base64.getEncoder().encodeToString("x".toByteArray()) to "a payload shorter than an IV",
            tooShort to "a payload shorter than IV plus tag",
            "V1:abcd" to "a different prefix",
            "" to "an empty string",
        ).forEach { (blob, what) ->
            assertEquals(what, OpenResult.Rejected, vault.open(blob))
        }
        val garbage = "v1:" + Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
        assertEquals(OpenResult.Rejected, vault.open(garbage))
    }

    @Test
    fun `any Keystore or provider failure is unavailable, not rejected`() {
        val sealed = vault.seal("SAPISID=abc")
        val failures: List<Throwable> =
            listOf(
                ProviderException("keystore.db corrupted"),
                java.security.KeyStoreException("keystore not ready"),
                java.security.UnrecoverableKeyException("key not loadable"),
                java.security.InvalidKeyException("key rejected"),
                IllegalStateException("provider crashed"),
            )
        for (failure in failures) {
            val broken = CookieVault(FailingKeyProvider(failure))
            assertEquals(failure.toString(), OpenResult.Unavailable, broken.open(sealed))
        }
    }

    @Test
    fun `a value that is rejected does not become unavailable when the Keystore breaks later`() {
        val broken = CookieVault(FailingKeyProvider(ProviderException("down")))

        // Malformed input is judged before the Keystore is touched.
        assertEquals(OpenResult.Rejected, broken.open("SAPISID=plaintext"))
        assertEquals(OpenResult.Rejected, broken.open("v1:!!!"))
    }

    @Test
    fun `openOrNull reads both non-answers as null`() {
        val sealed = vault.seal("secret")

        assertEquals("secret", vault.openOrNull(sealed))
        assertNull(vault.openOrNull("plaintext"))
        assertNull(CookieVault(FailingKeyProvider(ProviderException("down"))).openOrNull(sealed))
    }

    @Test
    fun `an opened result never prints the secret`() {
        assertFalse(OpenResult.Opened("SAPISID=abc").toString().contains("SAPISID"))
    }

    private class FailingKeyProvider(
        private val failure: Throwable,
    ) : AesGcmKeyProvider {
        override fun key(): SecretKey = throw failure
    }
}

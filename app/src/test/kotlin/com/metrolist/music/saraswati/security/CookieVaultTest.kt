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
import java.util.Base64

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
}

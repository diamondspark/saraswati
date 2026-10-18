/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: at-rest seal for the YouTube sign-in cookie. The cookie
// contains SAPISID and the other YouTube auth cookies — plaintext in
// DataStore means plaintext in every cloud backup and every phone-to-phone
// transfer (NEW-3), so it is sealed with a Keystore-backed AES-256-GCM key
// that never leaves this device. Format: "v1:" + Base64(iv[12] ||
// ciphertext || tag[16]). The AAD binds the ciphertext to this specific
// use, so a value sealed for cookie storage can't be silently replayed
// into a different field even if DataStore's key namespace were ever
// confused (defense in depth; DataStore uses distinct keys anyway).
package com.metrolist.music.saraswati.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class CookieVault(
    private val keys: AesGcmKeyProvider,
) {
    private val secureRandom = SecureRandom()

    fun seal(plain: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(AAD)
        val cipherTextAndTag = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = iv + cipherTextAndTag
        return PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    /**
     * Returns the original plaintext, or null if [stored] is not our
     * format ("v1:..."), was tampered with, or was sealed under a
     * different key (including a key from a different device).
     */
    fun open(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val payload = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            if (payload.size < IV_LENGTH_BYTES) return null
            val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
            val cipherTextAndTag = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.updateAAD(AAD)
            String(cipher.doFinal(cipherTextAndTag), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // Wrong key, or the tag didn't verify (tampered ciphertext).
            null
        } catch (e: IllegalArgumentException) {
            // Malformed Base64.
            null
        }
    }

    private companion object {
        const val PREFIX = "v1:"
        val AAD = "saraswati.cookie.v1".toByteArray(Charsets.UTF_8)
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

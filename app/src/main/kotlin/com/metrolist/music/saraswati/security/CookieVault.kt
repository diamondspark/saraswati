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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class CookieVault(
    private val keys: AesGcmKeyProvider,
) {
    fun seal(plain: String): String {
        // SARASWATI: a real Android Keystore key defaults to
        // setRandomizedEncryptionRequired(true) and REJECTS a caller-
        // supplied IV (InvalidAlgorithmParameterException: "Caller-provided
        // IV not permitted") — only JCE software keys tolerate it, which is
        // why this was invisible under SoftwareKeyProvider-only tests. Let
        // the provider generate the IV and read back whatever it actually
        // used; do not weaken the key by requesting unrandomized
        // encryption just to keep a caller-supplied IV.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH_BYTES) {
            "Expected a ${IV_LENGTH_BYTES}-byte GCM IV from the provider, got ${iv.size}"
        }
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
        } catch (e: RuntimeException) {
            // SARASWATI: a broken Keystore (corrupted keystore.db, a
            // provider crash, etc.) can throw an unchecked
            // java.security.ProviderException from cipher init/doFinal, not
            // just from key generation. open() is documented to never
            // throw — callers on the main thread (rememberPreference,
            // HomeViewModel's collector) must not crash on a bad Keystore.
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

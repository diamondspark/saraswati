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
//
// SARASWATI: Task 6 review — the binding is per slot: AAD =
// "saraswati.<purpose>.v1". The default purpose is "cookie", which is the
// AAD every sealed cookie on a phone already carries, so existing cookies
// keep opening. The family hub credentials each seal under their own
// purpose (SealedPreferences.purposeOf), so a ciphertext lifted from one
// slot does not open in another.
package com.metrolist.music.saraswati.security

import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class CookieVault(
    private val keys: AesGcmKeyProvider,
) {
    fun seal(
        plain: String,
        purpose: String = COOKIE_PURPOSE,
    ): String {
        require(isValidPurpose(purpose)) { "invalid seal purpose" }
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
        cipher.updateAAD(aad(purpose))
        val cipherTextAndTag = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = iv + cipherTextAndTag
        return PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    /**
     * Opens [stored]. Three answers, because they call for three different
     * reactions (SARASWATI: fix wave A, I1):
     *
     * - [OpenResult.Opened]: the original plaintext.
     * - [OpenResult.Rejected]: the value is not ours and never will be: no
     *   "v1:" prefix, malformed Base64, a ciphertext too short to hold an IV
     *   and tag, a tag that does not verify (tampering, another device's
     *   key, a value sealed for a different [purpose]). Only this answer
     *   justifies deleting the value.
     * - [OpenResult.Unavailable]: the Keystore or the crypto provider threw.
     *   The value may be perfectly good and a later attempt may open it, so a
     *   caller keeps it and tries again; it must never wipe on this.
     *
     * Never throws.
     */
    fun open(
        stored: String,
        purpose: String = COOKIE_PURPOSE,
    ): OpenResult {
        if (!looksSealed(stored) || !isValidPurpose(purpose)) return OpenResult.Rejected
        val payload =
            try {
                Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            } catch (e: IllegalArgumentException) {
                return OpenResult.Rejected
            }
        if (payload.size < IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) return OpenResult.Rejected
        return try {
            val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
            val cipherTextAndTag = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.updateAAD(aad(purpose))
            OpenResult.Opened(String(cipher.doFinal(cipherTextAndTag), Charsets.UTF_8))
        } catch (e: BadPaddingException) {
            // AEADBadTagException is a BadPaddingException: the tag did not
            // verify, so this ciphertext is not ours (wrong key, wrong slot,
            // tampered).
            OpenResult.Rejected
        } catch (e: Exception) {
            // SARASWATI: anything else (a KeyStoreException, a ProviderException
            // from a broken keystore.db or a custom ROM, a key that cannot be
            // loaded right now) says nothing about the value. open() is
            // documented to never throw — callers on the main thread must not
            // crash on a bad Keystore.
            OpenResult.Unavailable
        }
    }

    /** The plaintext, or null for both a rejected value and an unavailable Keystore. For callers that only read. */
    fun openOrNull(
        stored: String,
        purpose: String = COOKIE_PURPOSE,
    ): String? = (open(stored, purpose) as? OpenResult.Opened)?.plaintext

    companion object {
        /** True when [stored] has the vault's own prefix (it may still fail to open). Needs no Keystore. */
        fun looksSealed(stored: String): Boolean = stored.startsWith(PREFIX)

        private const val PREFIX = "v1:"
        private const val COOKIE_PURPOSE = "cookie"

        // Letters, digits and underscore only, so no purpose can add a
        // separator that makes two different purposes produce the same AAD.
        private val PURPOSE = Regex("[A-Za-z0-9_]{1,64}")

        private fun isValidPurpose(purpose: String): Boolean = PURPOSE.matches(purpose)

        private fun aad(purpose: String): ByteArray = "saraswati.$purpose.v1".toByteArray(Charsets.UTF_8)

        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** What [CookieVault.open] found; see there for what each answer licenses a caller to do. */
sealed interface OpenResult {
    /** [toString] never prints the secret. */
    data class Opened(
        val plaintext: String,
    ) : OpenResult {
        override fun toString(): String = "Opened(<redacted>)"
    }

    data object Rejected : OpenResult

    data object Unavailable : OpenResult
}

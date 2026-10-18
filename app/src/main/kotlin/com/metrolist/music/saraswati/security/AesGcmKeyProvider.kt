/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: the AES-256-GCM key that CookieVault uses to seal the YouTube
// sign-in cookie (and any other SecretPreferenceKeys value) at rest. The
// Android Keystore never exposes raw key material to the app process; the
// key never leaves it, not even into this process's heap, so a plaintext
// cookie extracted from a backup or a rooted device's /data is useless
// without this specific device's Keystore.
package com.metrolist.music.saraswati.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface AesGcmKeyProvider {
    fun key(): SecretKey
}

/**
 * Keystore-backed AES-256-GCM key, StrongBox-backed when the device has a
 * StrongBox (a separate secure-element chip), falling back to the normal
 * TEE-backed hardware key otherwise. No user authentication is required:
 * the app must be able to read the cookie right after boot with no unlock
 * prompt, so requiring auth would just break playback after a restart.
 */
class AndroidKeystoreKeyProvider(
    private val alias: String = "saraswati_cookie_v1",
) : AesGcmKeyProvider {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            generator.init(buildSpec(strongBoxBacked = true))
            generator.generateKey()
        } catch (e: Exception) {
            // StrongBox unavailable on this device (most phones don't have
            // one); fall back to the default hardware-backed key.
            generator.init(buildSpec(strongBoxBacked = false))
            generator.generateKey()
        }
    }

    private fun buildSpec(strongBoxBacked: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(strongBoxBacked)
            .build()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * In-memory AES-256 key for JVM unit tests, where the Android Keystore
 * does not exist. Never used by the shipped app.
 */
class SoftwareKeyProvider : AesGcmKeyProvider {
    private val secretKey: SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun key(): SecretKey = secretKey
}

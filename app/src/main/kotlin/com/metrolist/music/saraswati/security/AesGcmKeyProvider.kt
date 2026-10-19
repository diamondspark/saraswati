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
 *
 * SARASWATI: fix wave A (I1), wave B2 — a key is generated only when the Keystore
 * says, without an error, that there is no key under the alias: [KeyStoreAccess.load]
 * (KeyStore.getKey) comes back null. It used to be gated on containsAlias, but
 * AndroidKeyStoreSpi.containsAlias can swallow a transient error and answer false for
 * a key that is there, and generating then overwrites the live key and makes every
 * value sealed under it permanently unreadable. getKey reports a failure by throwing,
 * so any exception, from opening the Keystore or from the load, goes straight to the
 * caller ([CookieVault.open] reports it as Unavailable) and nothing is generated; the
 * next call tries again. The Keystore itself is opened on first use, not at
 * construction. [key] is synchronized so two threads on a fresh install cannot both
 * find the alias empty and generate twice.
 */
class AndroidKeystoreKeyProvider internal constructor(
    private val store: KeyStoreAccess,
) : AesGcmKeyProvider {
    constructor(alias: String = "saraswati_cookie_v1") : this(RealKeyStoreAccess(alias))

    @Synchronized
    override fun key(): SecretKey = store.load() ?: store.generate()
}

/** The two Keystore operations [AndroidKeystoreKeyProvider] decides between; a seam so a JVM test can fail each. */
internal interface KeyStoreAccess {
    /** The key under the alias, or null only if the Keystore reports there is none; every failure throws. */
    fun load(): SecretKey?

    fun generate(): SecretKey
}

private class RealKeyStoreAccess(
    private val alias: String,
) : KeyStoreAccess {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override fun load(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    override fun generate(): SecretKey {
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
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

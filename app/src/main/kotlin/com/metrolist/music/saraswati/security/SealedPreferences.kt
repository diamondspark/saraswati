/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: the one place that decides which preference values are sealed at
// rest and how they are sealed, opened and written. DataStore.kt's helpers
// (the read operators, rememberPreference) used to hard-code this for
// InnerTubeCookieKey alone; they now delegate here, so the family hub
// credentials follow exactly the cookie's rules (Keystore-backed AES-256-GCM
// via CookieVault) and a JVM test can drive the rules with a software key.
//
// Which keys are sealed is SecretPreferenceKeys.VAULT_SEALED. A key outside
// that set passes through every function here untouched.
package com.metrolist.music.saraswati.security

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

object SealedPreferences {
    fun isSealed(key: Preferences.Key<*>): Boolean = key in SecretPreferenceKeys.VAULT_SEALED

    /**
     * The plaintext behind a stored value. For a sealed key this is null
     * unless [raw] is a value [vault] itself sealed: a plaintext or
     * foreign-sealed value (a restored backup, another phone's) is never
     * returned.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> unseal(
        key: Preferences.Key<T>,
        raw: T?,
        vault: CookieVault,
    ): T? =
        if (isSealed(key)) {
            (raw as? String)?.let { vault.open(it) } as T?
        } else {
            raw
        }

    @Suppress("UNCHECKED_CAST")
    fun <T> seal(
        key: Preferences.Key<T>,
        value: T,
        vault: CookieVault,
    ): T =
        if (isSealed(key)) {
            vault.seal(value as String) as T
        } else {
            value
        }

    /**
     * Stores [value] for [key], sealed if the key is. An empty string for a
     * sealed key means "no credential" (a sign-out writes ""), so the key is
     * removed instead of sealing nothing.
     */
    fun <T> write(
        prefs: MutablePreferences,
        key: Preferences.Key<T>,
        value: T,
        vault: CookieVault,
    ) {
        if (isSealed(key) && (value as String).isEmpty()) {
            prefs.remove(key)
            return
        }
        prefs[key] = seal(key, value, vault)
    }
}

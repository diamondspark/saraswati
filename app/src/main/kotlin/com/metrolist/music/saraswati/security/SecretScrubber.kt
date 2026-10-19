/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: runs once at app startup (App.onCreate, before anything reads
// the cookie — see the ordering note there). A restored Metrolist cloud
// backup, a phone-to-phone transfer, or a legacy datastore file copied in
// by hand can carry a plaintext YouTube sign-in cookie or an integration
// token (audit NEW-3; Review Focus #4). This removes every key
// SecretPreferenceKeys.all names, except a SecretPreferenceKeys.VAULT_SEALED
// key (the cookie, the family hub credentials) whose value the vault
// actually accepts. A plaintext or foreign-sealed value is wiped, not
// "repaired" — nothing downstream ever has to guess whether a stored value
// is trustworthy.
package com.metrolist.music.saraswati.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit

object SecretScrubber {
    suspend fun scrub(
        dataStore: DataStore<Preferences>,
        vault: CookieVault,
    ) {
        dataStore.edit { settings ->
            for (key in SecretPreferenceKeys.all) {
                if (key in SecretPreferenceKeys.VAULT_SEALED && settings.holdsVaultSealedValue(key, vault)) continue
                settings.removeAny(key)
            }
        }
    }

    // A sealed key's value is a String by construction (VAULT_SEALED only
    // holds string keys), so the cast is safe; an absent key is not sealed.
    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.holdsVaultSealedValue(
        key: Preferences.Key<*>,
        vault: CookieVault,
    ): Boolean = (this[key as Preferences.Key<Any>] as? String)?.let { vault.open(it) != null } ?: false

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.removeAny(key: Preferences.Key<*>) {
        // Preferences.Key<T> is invariant, but MutablePreferences.remove()
        // only ever uses the key's string name to look up the backing map
        // entry — the type parameter is a read-side convenience, not
        // something the removal path depends on — so this cast is safe
        // regardless of the key's real T.
        remove(key as Preferences.Key<Any>)
    }
}

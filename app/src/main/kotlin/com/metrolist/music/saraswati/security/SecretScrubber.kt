/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: runs once at app startup (App.onCreate, before anything reads
// the cookie — see the ordering note there). A restored Metrolist cloud
// backup, a phone-to-phone transfer, or a legacy datastore file copied in
// by hand can carry a plaintext YouTube sign-in cookie or an integration
// token (audit NEW-3; Review Focus #4). This removes every key
// SecretPreferenceKeys.all names, except an InnerTubeCookieKey value the
// vault actually accepts. A plaintext or foreign-sealed cookie is wiped,
// not "repaired" — nothing downstream ever has to guess whether a stored
// value is trustworthy.
package com.metrolist.music.saraswati.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.InnerTubeCookieKey

object SecretScrubber {
    suspend fun scrub(
        dataStore: DataStore<Preferences>,
        vault: CookieVault,
    ) {
        dataStore.edit { settings ->
            val cookieIsVaultSealed = settings[InnerTubeCookieKey]?.let { vault.open(it) != null } ?: false
            for (key in SecretPreferenceKeys.all) {
                if (key == InnerTubeCookieKey && cookieIsVaultSealed) continue
                settings.removeAny(key)
            }
        }
    }

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

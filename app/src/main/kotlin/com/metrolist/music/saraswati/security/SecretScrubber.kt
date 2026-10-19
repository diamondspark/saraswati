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
//
// SARASWATI: fix wave A (I1, I4) — two phases, so the part that must finish
// before the first read needs no Keystore and the part that needs the
// Keystore can run off the main thread without anyone waiting for it:
//
//  1. [scrubPlaintext] wipes everything that is not a vault-sealed-looking
//     value. No Keystore. Blocking this is what guarantees no plaintext secret
//     is ever read.
//  2. [scrubSealed] opens each remaining sealed value. Only a value the vault
//     REJECTS (bad tag, another device's key, another slot) is wiped. A
//     Keystore that is merely UNAVAILABLE (a hiccup at startup) keeps the
//     value and the next start tries again — deleting the hub credentials
//     there would strand a grandparent's phone. Nothing reads a sealed value
//     without opening it, so reads never depend on this phase having run.
package com.metrolist.music.saraswati.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

object SecretScrubber {
    suspend fun scrub(
        dataStore: DataStore<Preferences>,
        vault: CookieVault,
    ) {
        scrubPlaintext(dataStore)
        scrubSealed(dataStore, vault)
    }

    /** Phase 1: wipes every secret key except a vault-sealed key holding a value in the vault's format. Needs no Keystore. */
    suspend fun scrubPlaintext(dataStore: DataStore<Preferences>) {
        dataStore.edit { settings ->
            for (key in SecretPreferenceKeys.all) {
                if (key in SecretPreferenceKeys.VAULT_SEALED && settings.holdsSealedLookingValue(key)) continue
                settings.removeAny(key)
            }
        }
    }

    /**
     * Phase 2: wipes a vault-sealed value only when [vault] rejects it. The Keystore work happens on
     * a snapshot, outside the DataStore write, and a value that changed meanwhile (a fresh sign-in)
     * is left alone.
     */
    suspend fun scrubSealed(
        dataStore: DataStore<Preferences>,
        vault: CookieVault,
    ) {
        val snapshot = dataStore.data.first()
        val rejected = mutableMapOf<Preferences.Key<*>, String>()
        for (key in SecretPreferenceKeys.VAULT_SEALED) {
            val stored = snapshot.sealedString(key) ?: continue
            if (vault.open(stored, SealedPreferences.purposeOf(key)) == OpenResult.Rejected) rejected[key] = stored
        }
        if (rejected.isEmpty()) return
        dataStore.edit { settings ->
            for ((key, stored) in rejected) {
                if (settings.sealedString(key) == stored) settings.removeAny(key)
            }
        }
    }

    // A sealed key's value is a String by construction (VAULT_SEALED only
    // holds string keys), so the cast is safe; an absent key is not sealed.
    @Suppress("UNCHECKED_CAST")
    private fun Preferences.sealedString(key: Preferences.Key<*>): String? = this[key as Preferences.Key<Any>] as? String

    private fun MutablePreferences.holdsSealedLookingValue(key: Preferences.Key<*>): Boolean =
        sealedString(key)?.let(CookieVault::looksSealed) ?: false

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

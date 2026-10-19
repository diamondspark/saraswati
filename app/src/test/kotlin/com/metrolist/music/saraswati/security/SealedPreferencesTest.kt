/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 6 — the seal/open/write rules DataStore.kt's helpers
// delegate to, for the YouTube cookie and the three family hub credentials.
// They live here (rather than as private helpers inside DataStore.kt) so a
// JVM test can run them against a software key instead of the Android
// Keystore.
package com.metrolist.music.saraswati.security

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.FamilyAdminKeyKey
import com.metrolist.music.constants.FamilyHubClaimSecretKey
import com.metrolist.music.constants.FamilyHubSessionTokenKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.PureBlackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedPreferencesTest {
    private val vault = CookieVault(SoftwareKeyProvider())
    private val sealedKeys =
        listOf(InnerTubeCookieKey, FamilyAdminKeyKey, FamilyHubSessionTokenKey, FamilyHubClaimSecretKey)

    @Test
    fun `the cookie and every hub credential are sealed keys`() {
        for (key in sealedKeys) {
            assertTrue(key.name, SealedPreferences.isSealed(key))
        }
    }

    @Test
    fun `every sealed key is also a scrubbed secret`() {
        assertTrue(SecretPreferenceKeys.all.containsAll(SecretPreferenceKeys.VAULT_SEALED))
    }

    @Test
    fun `seal produces a vault value that opens back to the plaintext`() {
        for (key in sealedKeys) {
            val stored = SealedPreferences.seal(key, "the-secret", vault)

            assertNotEquals(key.name, "the-secret", stored)
            assertFalse(key.name, stored.contains("the-secret"))
            assertEquals(key.name, "the-secret", SealedPreferences.unseal(key, stored, vault))
        }
    }

    @Test
    fun `unseal refuses plaintext and values sealed by another device`() {
        val otherDevice = CookieVault(SoftwareKeyProvider())
        for (key in sealedKeys) {
            assertNull(key.name, SealedPreferences.unseal(key, "plaintext-from-a-backup", vault))
            assertNull(key.name, SealedPreferences.unseal(key, otherDevice.seal("not-yours"), vault))
        }
    }

    @Test
    fun `unseal of an absent value is absent`() {
        assertNull(SealedPreferences.unseal(FamilyAdminKeyKey, null, vault))
    }

    @Test
    fun `keys outside the sealed set pass through untouched`() {
        assertFalse(SealedPreferences.isSealed(AccountNameKey))
        assertFalse(SealedPreferences.isSealed(PureBlackKey))
        assertEquals("Mummy", SealedPreferences.seal(AccountNameKey, "Mummy", vault))
        assertEquals("Mummy", SealedPreferences.unseal(AccountNameKey, "Mummy", vault))
        assertEquals(true, SealedPreferences.unseal(PureBlackKey, true, vault))
    }

    @Test
    fun `write stores the sealed form of a sealed key`() {
        for (key in sealedKeys) {
            val prefs = mutablePreferencesOf()

            SealedPreferences.write(prefs, key, "the-secret", vault)

            val stored = prefs[key]
            assertNotEquals(key.name, "the-secret", stored)
            assertEquals(key.name, "the-secret", SealedPreferences.unseal(key, stored, vault))
        }
    }

    @Test
    fun `write of an empty string removes a sealed key instead of sealing nothing`() {
        for (key in sealedKeys) {
            val prefs = mutablePreferencesOf(key to SealedPreferences.seal(key, "old", vault))

            SealedPreferences.write(prefs, key, "", vault)

            assertNull(key.name, prefs[key])
        }
    }

    @Test
    fun `write leaves an ordinary key as the plain value`() {
        val prefs = mutablePreferencesOf()

        SealedPreferences.write(prefs, AccountNameKey, "Mummy", vault)
        SealedPreferences.write(prefs, PureBlackKey, true, vault)

        assertEquals("Mummy", prefs[AccountNameKey])
        assertEquals(true, prefs[PureBlackKey])
        assertEquals(2, prefs.asMap().size)
    }
    // SARASWATI: the Task 6 review found every sealed slot shared the
    // cookie's AAD, so a ciphertext sealed for one slot opened in another.
    @Test
    fun `a value sealed for one slot does not unseal in any other slot`() {
        for (from in sealedKeys) {
            val sealed = SealedPreferences.seal(from, "value-for-${from.name}", vault)
            for (into in sealedKeys) {
                if (into == from) {
                    assertEquals(from.name, "value-for-${from.name}", SealedPreferences.unseal(into, sealed, vault))
                } else {
                    assertNull("${from.name} -> ${into.name}", SealedPreferences.unseal(into, sealed, vault))
                }
            }
        }
    }

    @Test
    fun `every sealed slot has its own purpose and only the cookie keeps the original one`() {
        val purposes = sealedKeys.map { SealedPreferences.purposeOf(it) }

        assertEquals(purposes.size, purposes.toSet().size)
        assertEquals("cookie", SealedPreferences.purposeOf(InnerTubeCookieKey))
        assertEquals(sealedKeys.size - 1, purposes.count { it != "cookie" })
    }
}

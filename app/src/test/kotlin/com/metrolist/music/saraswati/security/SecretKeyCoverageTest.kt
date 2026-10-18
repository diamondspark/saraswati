/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: a future upstream merge can add a new token/key/session/
// password/cookie preference without anyone on this fork noticing. This
// reflects over every Preferences.Key declared in PreferenceKeys.kt and
// fails the build unless each secret-shaped one is a conscious decision:
// either scrubbed (SecretPreferenceKeys.all) or explicitly excused with a
// reason (SecretPreferenceKeys.NOT_SECRET).
package com.metrolist.music.saraswati.security

import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class SecretKeyCoverageTest {
    @Test
    fun `every secret-shaped preference key is in SecretPreferenceKeys`() {
        val secretShapedName = Regex("(?i)(cookie|token|apikey|api_key|secret|session|password)")
        val preferenceKeysClass = Class.forName("com.metrolist.music.constants.PreferenceKeysKt")

        val secretShapedFields =
            preferenceKeysClass.declaredFields
                .filter { Modifier.isStatic(it.modifiers) }
                .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
                .filter { secretShapedName.containsMatchIn(it.name) }
                .onEach { it.isAccessible = true }

        // Sanity check: if this finds nothing, the reflection target
        // (class name, or Preferences.Key type) is wrong, not that the
        // app suddenly has zero secret-shaped preferences.
        assertTrue(
            "Expected to find secret-shaped preference keys (e.g. InnerTubeCookieKey) " +
                "via reflection on PreferenceKeysKt — did the file get renamed or moved?",
            secretShapedFields.isNotEmpty(),
        )

        val unaccountedFor =
            secretShapedFields.filterNot { field ->
                @Suppress("UNCHECKED_CAST")
                val key = field.get(null) as Preferences.Key<*>
                key in SecretPreferenceKeys.all || key in SecretPreferenceKeys.NOT_SECRET
            }

        assertTrue(
            "These preference keys look secret-shaped by name but are neither scrubbed " +
                "(SecretPreferenceKeys.all) nor excused with a reason " +
                "(SecretPreferenceKeys.NOT_SECRET): " +
                unaccountedFor.joinToString(", ") { it.name },
            unaccountedFor.isEmpty(),
        )
    }
}

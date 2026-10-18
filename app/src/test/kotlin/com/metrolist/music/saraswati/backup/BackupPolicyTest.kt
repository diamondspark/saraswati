/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the backup/restore allowlist (audit
// A6.3, A10-2, A10-3 — a crafted or legacy backup must not be able to
// plant a cookie, proxy, or server/base URL into the live settings, and a
// legitimate export must not leak the signed-in account's identity).
package com.metrolist.music.saraswati.backup

import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.DiscordAvatarKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMSessionKey
import com.metrolist.music.constants.ListenTogetherServerUrlKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.ProxyPasswordKey
import com.metrolist.music.constants.ProxyUrlKey
import com.metrolist.music.constants.SongSortTypeKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.saraswati.security.SecretPreferenceKeys
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.Modifier

class BackupPolicyTest {
    @Test
    fun `sanitize drops the cookie and account identity keys`() {
        val prefs =
            mutablePreferencesOf(
                InnerTubeCookieKey to "SAPISID=abc; HSID=def",
                VisitorDataKey to "visitor-123",
                DataSyncIdKey to "sync-456",
                AccountNameKey to "Jane Doe",
                AccountEmailKey to "jane@example.com",
                AccountChannelHandleKey to "@janedoe",
            ).toPreferences()

        val sanitized = BackupPolicy.sanitize(prefs)

        assertNull(sanitized[InnerTubeCookieKey])
        assertNull(sanitized[VisitorDataKey])
        assertNull(sanitized[DataSyncIdKey])
        assertNull(sanitized[AccountNameKey])
        assertNull(sanitized[AccountEmailKey])
        assertNull(sanitized[AccountChannelHandleKey])
    }

    @Test
    fun `sanitize drops proxy and server keys`() {
        val prefs =
            mutablePreferencesOf(
                ProxyUrlKey to "attacker.example.com:8080",
                ProxyPasswordKey to "hunter2",
                ListenTogetherServerUrlKey to "wss://attacker.example.com/ws",
            ).toPreferences()

        val sanitized = BackupPolicy.sanitize(prefs)

        assertNull(sanitized[ProxyUrlKey])
        assertNull(sanitized[ProxyPasswordKey])
        assertNull(sanitized[ListenTogetherServerUrlKey])
    }

    @Test
    fun `sanitize drops AI, Last-fm and Discord keys`() {
        val prefs =
            mutablePreferencesOf(
                OpenRouterApiKey to "sk-or-abc123",
                OpenRouterBaseUrlKey to "https://attacker.example.com/v1/chat/completions",
                DeeplApiKey to "deepl-xyz789",
                LastFMSessionKey to "lastfm-session-token",
                DiscordAvatarKey to "https://cdn.discordapp.com/avatars/12345/abcdef.png",
            ).toPreferences()

        val sanitized = BackupPolicy.sanitize(prefs)

        assertNull(sanitized[OpenRouterApiKey])
        assertNull(sanitized[OpenRouterBaseUrlKey])
        assertNull(sanitized[DeeplApiKey])
        assertNull(sanitized[LastFMSessionKey])
        assertNull(sanitized[DiscordAvatarKey])
    }

    @Test
    fun `sanitize keeps theme, playback and library preferences`() {
        val prefs =
            mutablePreferencesOf(
                DarkModeKey to "DARK",
                AudioQualityKey to "HIGH",
                SongSortTypeKey to "NAME",
            ).toPreferences()

        val sanitized = BackupPolicy.sanitize(prefs)

        assertEquals("DARK", sanitized[DarkModeKey])
        assertEquals("HIGH", sanitized[AudioQualityKey])
        assertEquals("NAME", sanitized[SongSortTypeKey])
    }

    @Test
    fun `readLimited returns the bytes under the cap`() {
        val data = ByteArray(100) { it.toByte() }
        val result = BackupPolicy.readLimited(ByteArrayInputStream(data), maxBytes = 1000L)
        assertEquals(100, result.size)
        assertTrue(result.contentEquals(data))
    }

    @Test
    fun `readLimited throws above the cap`() {
        val data = ByteArray(2000)
        assertThrows(BackupTooLargeException::class.java) {
            BackupPolicy.readLimited(ByteArrayInputStream(data), maxBytes = 1000L)
        }
    }

    @Test
    fun `readLimited never reads more than maxBytes plus one`() {
        var bytesRead = 0L
        val countingStream =
            object : java.io.InputStream() {
                private val delegate = ByteArrayInputStream(ByteArray(1_000_000))

                override fun read(): Int = throw UnsupportedOperationException("not used")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    val n = delegate.read(b, off, len)
                    if (n > 0) bytesRead += n
                    return n
                }
            }

        assertThrows(BackupTooLargeException::class.java) {
            BackupPolicy.readLimited(countingStream, maxBytes = 1000L)
        }
        assertTrue("expected at most 1001 bytes read, got $bytesRead", bytesRead <= 1001L)
    }

    @Test
    fun `every sensitive-looking preference key is in NEVER_EXPORT_OR_RESTORE or in an explicit SAFE set`() {
        val sensitiveShapedName =
            Regex("(?i)(cookie|token|apikey|api_key|secret|session|password|proxy|server|url|visitor|datasync|email|account)")
        val preferenceKeysClass = Class.forName("com.metrolist.music.constants.PreferenceKeysKt")

        val sensitiveShapedFields =
            preferenceKeysClass.declaredFields
                .filter { Modifier.isStatic(it.modifiers) }
                .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
                .filter { sensitiveShapedName.containsMatchIn(it.name) }
                .onEach { it.isAccessible = true }

        assertTrue(
            "Expected to find sensitive-shaped preference keys (e.g. InnerTubeCookieKey) via " +
                "reflection on PreferenceKeysKt — did the file get renamed or moved?",
            sensitiveShapedFields.isNotEmpty(),
        )

        val unaccountedFor =
            sensitiveShapedFields.filterNot { field ->
                @Suppress("UNCHECKED_CAST")
                val key = field.get(null) as Preferences.Key<*>
                key in BackupPolicy.NEVER_EXPORT_OR_RESTORE || key in BackupPolicy.SAFE
            }

        assertTrue(
            "These preference keys look sensitive-shaped by name but are neither excluded from " +
                "backups (BackupPolicy.NEVER_EXPORT_OR_RESTORE) nor excused with a reason " +
                "(BackupPolicy.SAFE): " + unaccountedFor.joinToString(", ") { it.name },
            unaccountedFor.isEmpty(),
        )
    }

    @Test
    fun `NEVER_EXPORT_OR_RESTORE includes every SecretPreferenceKeys entry`() {
        assertTrue(BackupPolicy.NEVER_EXPORT_OR_RESTORE.containsAll(SecretPreferenceKeys.all))
    }
}

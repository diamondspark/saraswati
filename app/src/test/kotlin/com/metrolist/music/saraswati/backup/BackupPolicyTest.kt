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
import com.metrolist.music.constants.DiscordButton1UrlKey
import com.metrolist.music.constants.DiscordButton2UrlKey
import com.metrolist.music.constants.FamilyAdminKeyKey
import com.metrolist.music.constants.FamilyHubClaimSecretKey
import com.metrolist.music.constants.FamilyDeviceIdKey
import com.metrolist.music.constants.FamilyHubSessionTokenKey
import com.metrolist.music.constants.FamilyStationsSavedKey
import com.metrolist.music.constants.FamilyFeaturesDisabledKey
import com.metrolist.music.constants.FamilyPlaylistsCacheKey
import com.metrolist.music.constants.FamilyStationsCacheKey
import com.metrolist.music.constants.GrandparentRoleKey
import com.metrolist.music.constants.IsFamilyOwnerKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMSessionKey
import com.metrolist.music.constants.ListenTogetherServerUrlKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.ParivaarMixKey
import com.metrolist.music.constants.SmartDownloadLedgerKey
import com.metrolist.music.constants.StationOfflineSetsKey
import com.metrolist.music.constants.ParivaarPlaylistIdKey
import com.metrolist.music.constants.ParivaarTopSongsSyncedAtKey
import com.metrolist.music.constants.ProxyPasswordKey
import com.metrolist.music.constants.ProxyUrlKey
import com.metrolist.music.constants.SongSortTypeKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.saraswati.family.sangat.RemoteControllerStore
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

    // SARASWATI: Task 6 — the family hub credentials ride on
    // SecretPreferenceKeys.all, so a shared backup must not carry another
    // device's admin key or session token, and a hostile backup must not be
    // able to plant one into a live phone.
    @Test
    fun `sanitize drops the family hub credentials`() {
        val prefs =
            mutablePreferencesOf(
                FamilyAdminKeyKey to "admin-key",
                FamilyHubSessionTokenKey to "session-token",
                FamilyHubClaimSecretKey to "claim-secret",
            ).toPreferences()

        val sanitized = BackupPolicy.sanitize(prefs)

        assertNull(sanitized[FamilyAdminKeyKey])
        assertNull(sanitized[FamilyHubSessionTokenKey])
        assertNull(sanitized[FamilyHubClaimSecretKey])
    }

    @Test
    fun `a restore keeps this phone's own family hub credentials and ignores restored ones`() {
        val live =
            mutablePreferencesOf(
                FamilyAdminKeyKey to "live-sealed-admin",
                FamilyHubSessionTokenKey to "live-sealed-session",
            ).toPreferences()
        val restored =
            mutablePreferencesOf(
                FamilyAdminKeyKey to "planted-admin",
                FamilyHubClaimSecretKey to "planted-claim",
            ).toPreferences()

        val merged = BackupPolicy.mergeRestoreOntoLive(live = live, restored = restored)

        assertEquals("live-sealed-admin", merged[FamilyAdminKeyKey])
        assertEquals("live-sealed-session", merged[FamilyHubSessionTokenKey])
        assertNull(merged[FamilyHubClaimSecretKey])
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
                DiscordButton1UrlKey to "https://attacker.example.com/phish",
                DiscordButton2UrlKey to "https://attacker.example.com/phish2",
            ).toPreferences()

        val sanitized = BackupPolicy.sanitize(prefs)

        assertNull(sanitized[OpenRouterApiKey])
        assertNull(sanitized[OpenRouterBaseUrlKey])
        assertNull(sanitized[DeeplApiKey])
        assertNull(sanitized[LastFMSessionKey])
        assertNull(sanitized[DiscordAvatarKey])
        // SARASWATI: Discord was removed, so nothing reads these any more; an old backup's values are still
        // dropped rather than copied onto the phone.
        assertNull(sanitized[DiscordButton1UrlKey])
        assertNull(sanitized[DiscordButton2UrlKey])
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

    // SARASWATI: Task 9 — the grandparent flag is a cache of what the hub says,
    // and the owner's only way out of Saral is the hub. A backup (shared, old,
    // or hostile) must never be able to switch a phone into Saral, or out of it.
    @Test
    fun `sanitize drops the Saral flag and the stations cache`() {
        val prefs =
            mutablePreferencesOf(
                GrandparentRoleKey to true,
                FamilyStationsCacheKey to "{}",
                FamilyFeaturesDisabledKey to setOf("sangat"),
                ParivaarMixKey to "{}",
                StationOfflineSetsKey to "{}",
                SmartDownloadLedgerKey to "{}",
                FamilyPlaylistsCacheKey to "[]",
                ParivaarPlaylistIdKey to "PLabcdefghij",
                ParivaarTopSongsSyncedAtKey to 1L,
            ).toPreferences()

        val sanitized = BackupPolicy.sanitize(prefs)

        assertNull(sanitized[GrandparentRoleKey])
        assertNull(sanitized[FamilyStationsCacheKey])
        assertNull(sanitized[FamilyFeaturesDisabledKey])
        assertNull(sanitized[ParivaarMixKey])
        assertNull(sanitized[StationOfflineSetsKey])
        assertNull(sanitized[SmartDownloadLedgerKey])
        assertNull(sanitized[FamilyPlaylistsCacheKey])
        assertNull(sanitized[ParivaarPlaylistIdKey])
        assertNull(sanitized[ParivaarTopSongsSyncedAtKey])
    }

    @Test
    fun `a restore leaves this phone's Saral flag exactly as it is`() {
        val live = mutablePreferencesOf(GrandparentRoleKey to true).toPreferences()
        val restored = mutablePreferencesOf(AudioQualityKey to "HIGH").toPreferences()

        val merged = BackupPolicy.mergeRestoreOntoLive(live, restored)

        assertEquals(true, merged[GrandparentRoleKey])
    }

    @Test
    fun `a hostile backup cannot switch an adult phone into Saral`() {
        val live = mutablePreferencesOf(AudioQualityKey to "LOW").toPreferences()
        val restored = mutablePreferencesOf(GrandparentRoleKey to true).toPreferences()

        val merged = BackupPolicy.mergeRestoreOntoLive(live, BackupPolicy.sanitize(restored))

        assertNull(merged[GrandparentRoleKey])
    }
    // SARASWATI: fix round 1 — the owner flag, this phone's hub device id and
    // the "stations were saved" marker are this phone's own hub state. A
    // restore must not plant or clear them (a planted owner flag, or a device
    // id that no longer matches the live session token, leaves the phone
    // pointing at the wrong identity), and none of them belongs in a shared
    // backup. Their names do not match the sensitive-name regex above, so the
    // reflection test below pins them by prefix instead.
    @Test
    fun `sanitize drops the owner flag, the device id and the stations-saved marker`() {
        val prefs =
            mutablePreferencesOf(
                IsFamilyOwnerKey to true,
                FamilyDeviceIdKey to "device-1",
                FamilyStationsSavedKey to true,
                AudioQualityKey to "HIGH",
            ).toPreferences()

        val sanitized = BackupPolicy.sanitize(prefs)

        assertNull(sanitized[IsFamilyOwnerKey])
        assertNull(sanitized[FamilyDeviceIdKey])
        assertNull(sanitized[FamilyStationsSavedKey])
        assertEquals("HIGH", sanitized[AudioQualityKey])
    }

    @Test
    fun `a restore keeps this phone's own owner flag and device id and ignores planted ones`() {
        val live =
            mutablePreferencesOf(
                IsFamilyOwnerKey to true,
                FamilyDeviceIdKey to "live-device",
                FamilyHubSessionTokenKey to "live-sealed-session",
            ).toPreferences()
        val restored =
            mutablePreferencesOf(
                IsFamilyOwnerKey to false,
                FamilyDeviceIdKey to "planted-device",
                FamilyStationsSavedKey to true,
            ).toPreferences()

        val merged = BackupPolicy.mergeRestoreOntoLive(live, restored)

        assertEquals(true, merged[IsFamilyOwnerKey])
        assertEquals("live-device", merged[FamilyDeviceIdKey])
        assertEquals("live-sealed-session", merged[FamilyHubSessionTokenKey])
        assertNull(merged[FamilyStationsSavedKey])
    }

    @Test
    fun `a hostile backup cannot make a phone the owner or give it another device id`() {
        val live = mutablePreferencesOf(AudioQualityKey to "LOW").toPreferences()
        val restored =
            mutablePreferencesOf(
                IsFamilyOwnerKey to true,
                FamilyDeviceIdKey to "planted-device",
            ).toPreferences()

        val merged = BackupPolicy.mergeRestoreOntoLive(live, BackupPolicy.sanitize(restored))

        assertNull(merged[IsFamilyOwnerKey])
        assertNull(merged[FamilyDeviceIdKey])
    }

    @Test
    fun `every family hub state key is excluded from backups, whatever its name looks like`() {
        val hubState = Regex("^(Family|IsFamily|Grandparent)")
        val keys =
            Class
                .forName("com.metrolist.music.constants.PreferenceKeysKt")
                .declaredFields
                .filter { Modifier.isStatic(it.modifiers) }
                .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
                .filter { hubState.containsMatchIn(it.name) }
                .onEach { it.isAccessible = true }

        assertTrue("expected to find the family keys via reflection", keys.size >= 8)
        val missing =
            keys.filterNot { (it.get(null) as Preferences.Key<*>) in BackupPolicy.NEVER_EXPORT_OR_RESTORE }
        assertTrue(
            "These family hub preference keys can be exported or restored: " + missing.joinToString(", ") { it.name },
            missing.isEmpty(),
        )
    }

    // SARASWATI: Phase 4 review fix (6)
    @Test
    fun `a backup cannot plant or export the remote-controller flag`() {
        assertTrue(RemoteControllerStore.Key in BackupPolicy.NEVER_EXPORT_OR_RESTORE)
        val planted = mutablePreferencesOf(RemoteControllerStore.Key to true, SongSortTypeKey to "NAME").toPreferences()

        val sanitized = BackupPolicy.sanitize(planted)

        assertNull(sanitized[RemoteControllerStore.Key])
    }
}

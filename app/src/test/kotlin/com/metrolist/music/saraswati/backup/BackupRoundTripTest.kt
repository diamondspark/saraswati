/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: end-to-end regression test for the export -> ZIP bytes ->
// restore pipeline (audit A6.3, A10-2, A10-3): a fresh export must not
// carry the cookie or other NEVER_EXPORT_OR_RESTORE keys at the byte
// level, and restoring those bytes into a live DataStore must still not
// introduce them, while ordinary preferences like the theme survive.
package com.metrolist.music.saraswati.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.DiscordButton1UrlKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.ProxyPasswordKey
import com.metrolist.music.constants.ProxyUrlKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Minimal in-memory [DataStore] so this test needs no disk I/O or Android runtime. */
private class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

private suspend fun DataStore<Preferences>.edit(transform: suspend (MutablePreferences) -> Unit): Preferences =
    updateData { it.toMutablePreferences().apply { transform(this) }.toPreferences() }

private fun serialize(prefs: Preferences): ByteArray =
    runBlocking {
        val buffer = Buffer()
        PreferencesSerializer.writeTo(prefs, buffer)
        buffer.readByteArray()
    }

private fun deserialize(bytes: ByteArray): Preferences =
    runBlocking {
        PreferencesSerializer.readFrom(Buffer().write(bytes))
    }

class BackupRoundTripTest {
    @Test
    fun `export then restore keeps the theme and drops the cookie`() =
        runBlocking {
            val sourceDataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(
                        DarkModeKey to "DARK",
                        InnerTubeCookieKey to "SAPISID=abc; HSID=def456",
                    ),
                )

            // --- Export: the ZIP's settings entry is BackupPolicy.sanitize(current), serialized. ---
            val currentPrefs = sourceDataStore.data.first()
            val exportedBytes = serialize(BackupPolicy.sanitize(currentPrefs))

            // A fresh export must not carry the cookie at the byte level, not just via the
            // in-memory object — decoding the raw bytes back must still show it absent.
            val reparsedExport = deserialize(exportedBytes)
            assertNull(reparsedExport[InnerTubeCookieKey])
            assertEquals("DARK", reparsedExport[DarkModeKey])

            // --- Restore: parse the entry, sanitize again (defense in depth), write into the
            // live DataStore via edit {} rather than replacing a file. ---
            val targetDataStore = InMemoryPreferencesDataStore()
            val restoredPrefs = BackupPolicy.sanitize(deserialize(exportedBytes))
            targetDataStore.edit { prefs ->
                prefs.clear()
                prefs += restoredPrefs
            }

            val after = targetDataStore.data.first()
            assertEquals("DARK", after[DarkModeKey])
            assertNull(after[InnerTubeCookieKey])
        }

    @Test
    fun `restoring a hostile settings entry never plants a cookie`() =
        runBlocking {
            // Simulates a crafted backup file that tries to plant a cookie, a proxy
            // redirect, and a Discord Rich Presence button URL directly (not via the
            // app's own export path at all).
            val hostileBytes =
                serialize(
                    mutablePreferencesOf(
                        DarkModeKey to "LIGHT",
                        InnerTubeCookieKey to "SAPISID=stolen; HSID=stolen",
                        com.metrolist.music.constants.ProxyUrlKey to "attacker.example.com:9999",
                        DiscordButton1UrlKey to "https://attacker.example.com/phish",
                    ).toPreferences(),
                )

            val targetDataStore = InMemoryPreferencesDataStore()
            val restoredPrefs = BackupPolicy.sanitize(deserialize(hostileBytes))
            targetDataStore.edit { prefs ->
                prefs.clear()
                prefs += restoredPrefs
            }

            val after = targetDataStore.data.first()
            assertEquals("LIGHT", after[DarkModeKey])
            assertNull(after[InnerTubeCookieKey])
            assertNull(after[com.metrolist.music.constants.ProxyUrlKey])
            // SARASWATI (fix round 1): DiscordRpcManager reconnects automatically from a
            // Keystore-persisted token on every MusicService creation (no
            // SaraswatiFeatures.DISCORD check in that file) and publishes whatever is in
            // DataStore as the Rich Presence buttons on the next song play — so a planted
            // URL here would otherwise reach a live Discord presence on restart, no fresh
            // login required. This must never survive the restore's sanitize step.
            assertNull(after[DiscordButton1UrlKey])
        }

    @Test
    fun `a literal backup ZIP's settings entry has no cookie or proxy bytes anywhere`() =
        runBlocking {
            // Builds the exact ZIP container BackupRestoreViewModel.backup() writes (same
            // entry name, same sanitize-then-serialize settings bytes) without touching
            // Android Context/DataStore/Toast, then unzips it back and inspects the raw
            // bytes — the literal "unzip a fresh backup and assert it contains no cookie
            // key" check the brief calls for.
            val liveSettings =
                mutablePreferencesOf(
                    DarkModeKey to "DARK",
                    InnerTubeCookieKey to "SAPISID=realcookie; HSID=realcookie456",
                    ProxyUrlKey to "proxy.example.com:8080",
                    ProxyPasswordKey to "hunter2",
                    DiscordButton1UrlKey to "https://discord.example.com/mine",
                ).toPreferences()

            val zipBytes =
                ByteArrayOutputStream().use { out ->
                    ZipOutputStream(out).use { zip ->
                        zip.putNextEntry(ZipEntry("settings.preferences_pb"))
                        zip.write(serialize(BackupPolicy.sanitize(liveSettings)))
                    }
                    out.toByteArray()
                }

            var sawSettingsEntry = false
            ZipInputStream(zipBytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "settings.preferences_pb") {
                        sawSettingsEntry = true
                        val bytes = zip.readBytes()

                        val restored = deserialize(bytes)
                        assertEquals("DARK", restored[DarkModeKey])
                        assertNull(restored[InnerTubeCookieKey])
                        assertNull(restored[ProxyUrlKey])
                        assertNull(restored[ProxyPasswordKey])
                        assertNull(restored[DiscordButton1UrlKey])

                        // Byte-level check, independent of the proto field parser above:
                        // the literal cookie/password strings must not appear anywhere in
                        // the entry's raw bytes.
                        val raw = bytes.decodeToString(throwOnInvalidSequence = false)
                        assertTrue(!raw.contains("realcookie"))
                        assertTrue(!raw.contains("hunter2"))
                        assertTrue(!raw.contains("discord.example.com"))
                    }
                    entry = zip.nextEntry
                }
            }
            assertTrue(sawSettingsEntry)
        }

    @Test
    fun `restoring into a signed-in device keeps the live cookie and updates the theme`() =
        runBlocking {
            // SARASWATI (I1): BackupRoundTripTest previously only ever restored into an
            // EMPTY target store, so it could never catch `prefs.clear(); prefs += restored`
            // wiping a cookie that was already live before the restore started — that bug
            // only shows up when the TARGET is signed in. This is that missing case: a
            // signed-in device (has a live sealed cookie, VisitorData, account identity)
            // restores a backup made on a *different* device with different settings.
            val liveSealedCookie = "v1:not-really-sealed-but-opaque-to-this-test=="
            val targetDataStore =
                InMemoryPreferencesDataStore(
                    mutablePreferencesOf(
                        DarkModeKey to "DARK",
                        InnerTubeCookieKey to liveSealedCookie,
                        com.metrolist.music.constants.AccountNameKey to "Live Account",
                    ),
                )

            // The backup being restored came from a different signed-in session with a
            // different theme; sanitize() already strips its cookie/account keys, same as
            // every real restore.
            val backupBytes =
                serialize(
                    BackupPolicy.sanitize(
                        mutablePreferencesOf(
                            DarkModeKey to "LIGHT",
                            InnerTubeCookieKey to "SAPISID=someone-elses-cookie",
                            com.metrolist.music.constants.AccountNameKey to "Someone Else",
                        ).toPreferences(),
                    ),
                )
            val restoredPrefs = BackupPolicy.sanitize(deserialize(backupBytes))

            // This is the exact sequence BackupRestoreViewModel.restore() now runs inside
            // its dataStore.edit {} block.
            val live = targetDataStore.data.first()
            val merged = BackupPolicy.mergeRestoreOntoLive(live = live, restored = restoredPrefs)
            targetDataStore.edit { prefs ->
                prefs.clear()
                prefs += merged
            }

            val after = targetDataStore.data.first()
            // The restored backup's own setting takes effect...
            assertEquals("LIGHT", after[DarkModeKey])
            // ...but the live secret/account-identity keys are completely unchanged, not
            // cleared and not overwritten with the backup's values.
            assertEquals(liveSealedCookie, after[InnerTubeCookieKey])
            assertEquals("Live Account", after[com.metrolist.music.constants.AccountNameKey])
        }
}

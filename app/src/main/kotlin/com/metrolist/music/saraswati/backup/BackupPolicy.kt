/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: audit A6.3, A10-2, A10-3, A10-4, A10-6 — the in-app backup ZIP
// and restore flow had no per-key allowlist (a crafted or legacy settings
// file could plant a cookie, a proxy, or a Listen Together/AI server URL)
// and no size caps on any entry it read. This is the single allowlist both
// export and restore now go through: sanitize() is applied to the settings
// Preferences before they are written into the ZIP (so a shared backup
// cannot leak the signed-in account) and again after they are read back out
// of a ZIP (so a hostile or legacy file cannot plant one of these keys into
// the live DataStore).
//
// NEVER_EXPORT_OR_RESTORE builds on SecretPreferenceKeys.all (Task 7) rather
// than re-deciding which keys are credentials — see that file for the
// cookie/proxy/AI/Last.fm/Listen-Together-token reasoning. This file only
// adds the keys Task 7 had no reason to cover: values that identify the
// YouTube account itself (not a credential, but still something a shared
// backup must not leak), a handful of other server/base-URL settings that
// could redirect this app's traffic, and two cache-size limits a hostile
// backup could otherwise abuse into filling the device's storage.
//
// BackupPolicyTest reflects over every Preferences.Key in PreferenceKeysKt
// whose name looks sensitive-shaped (the same technique as Task 7's
// SecretKeyCoverageTest, with a wider regex: this policy also cares about
// "server", "url", "visitor", "datasync" and "account", none of which are
// credentials by themselves) and fails the build unless each one is a
// conscious decision: either in NEVER_EXPORT_OR_RESTORE or in SAFE with a
// reason.
package com.metrolist.music.saraswati.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.DiscordAvatarKey
import com.metrolist.music.constants.DiscordButton1UrlKey
import com.metrolist.music.constants.DiscordButton2UrlKey
import com.metrolist.music.constants.DiscordNameKey
import com.metrolist.music.constants.DiscordUsernameKey
import com.metrolist.music.constants.FamilyDeviceIdKey
import com.metrolist.music.constants.FamilyFeaturesDisabledKey
import com.metrolist.music.constants.FamilyPlaylistsCacheKey
import com.metrolist.music.constants.FamilyStationsCacheKey
import com.metrolist.music.constants.FamilyStationsSavedKey
import com.metrolist.music.constants.FamilyUnauthorizedAtKey
import com.metrolist.music.constants.FamilyUnauthorizedCountKey
import com.metrolist.music.constants.GrandparentRoleKey
import com.metrolist.music.constants.IsFamilyOwnerKey
import com.metrolist.music.constants.ListenTogetherServerUrlKey
import com.metrolist.music.constants.ListenTogetherSessionTimestampKey
import com.metrolist.music.constants.MaxImageCacheSizeKey
import com.metrolist.music.constants.MaxSongCacheSizeKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.ParivaarMixKey
import com.metrolist.music.constants.ParivaarPlaylistIdKey
import com.metrolist.music.constants.ParivaarTopSongsSyncedAtKey
import com.metrolist.music.constants.SangatCarRoomCodeKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.saraswati.family.sangat.RemoteControllerStore
import com.metrolist.music.saraswati.security.SecretPreferenceKeys
import java.io.ByteArrayOutputStream
import java.io.InputStream

class BackupTooLargeException : Exception("Backup entry exceeds its size cap")

object BackupPolicy {
    // Export: never write these into the ZIP. Restore: never let a
    // restored/hostile settings entry set these in the live DataStore.
    // The two concerns share one allowlist on purpose — a key that is too
    // dangerous to restore is also too dangerous to have sitting in a
    // shared/cloud-synced backup file in the first place.
    val NEVER_EXPORT_OR_RESTORE: Set<Preferences.Key<*>> =
        SecretPreferenceKeys.all +
            setOf(
                // YouTube visitor data and data-sync id travel with the
                // cookie as part of how InnerTube identifies the signed-in
                // account/device; useless for playback alone, but still
                // part of the account's fingerprint (audit A10-2/A10-3).
                VisitorDataKey,
                DataSyncIdKey,
                // The signed-in account's display name, email and @handle.
                // Exporting these leaks who owns the account even though
                // the cookie itself is sealed/stripped — a shared backup
                // must not identify the family member who signed in.
                AccountNameKey,
                AccountEmailKey,
                AccountChannelHandleKey,
                // The Listen Together relay host. A restored/crafted value
                // here would point every future "join a room" attempt at an
                // attacker-run server instead of the real one (audit
                // A10-2's "LT server/session" finding).
                ListenTogetherServerUrlKey,
                // The AI lyrics-translation endpoint. OriginalLyrics.kt,
                // ExperimentalLyrics.kt and LyricsMenu.kt all read this key
                // directly to build the translate request — unlike the
                // rest of the AI-lyrics feature, it isn't gated behind
                // SaraswatiFeatures.AI_LYRICS, so a restored value really
                // would redirect that request to an attacker's host.
                OpenRouterBaseUrlKey,
                // A family member's Discord identity (shown on their
                // Discord profile via Rich Presence). Same reasoning as the
                // YouTube account name/email above: a shared backup must
                // not leak which person this install belongs to, on any
                // linked service, not just YouTube.
                DiscordUsernameKey,
                DiscordNameKey,
                DiscordAvatarKey,
                // SARASWATI (fix round 1): NOT safe to restore. Originally
                // classified SAFE on the (wrong) assumption that Discord's
                // OAuth token never survives a restart — it does:
                // DiscordTokenStore persists it Keystore-encrypted, and
                // DiscordRpcManager.init() (called unconditionally by
                // MusicService on every service creation, with no
                // SaraswatiFeatures.DISCORD check anywhere in that file)
                // retrieves it and reconnects automatically. MusicService
                // then reads these two keys straight from DataStore to
                // build the Rich Presence activity published on the next
                // song play. On a phone where Discord was already
                // connected, a hostile backup planting these would get
                // auto-published to the family member's live Discord
                // presence on the very next reconnect/song-play — no fresh
                // login needed, triggered by the restore's own app
                // restart. Exactly the "plant a URL into a live phone"
                // scenario this file exists to prevent.
                DiscordButton1UrlKey,
                DiscordButton2UrlKey,
                // Cache size caps (MB). A hostile backup setting these to
                // an oversized value doesn't leak anything, but it does
                // raise the eviction threshold the image/song cache uses —
                // letting normal use quietly fill the device's storage
                // (a denial-of-service against the whole phone, not just
                // this app). Not a credential; still not safe to restore
                // from an untrusted file.
                MaxImageCacheSizeKey,
                MaxSongCacheSizeKey,
                // SARASWATI: Task 9 — Saral's on/off is a cache of the role the
                // hub holds for this phone, and the owner's switch in Manage
                // Family is the only way out of it. A restored backup must not
                // be able to put an adult's phone into Saral (no Settings to
                // undo it from) or take a grandparent's out of it. The stations
                // cache is hub data, refreshed from the hub, not a backup item.
                GrandparentRoleKey,
                FamilyStationsCacheKey,
                // SARASWATI: Phase 4 Task 8 — the cached kill-switches are hub data too.
                FamilyFeaturesDisabledKey,
                // SARASWATI: Phase 4 Task 11 — the built Parivaar Mix is derived from the family's listening.
                ParivaarMixKey,
                // SARASWATI: Phase 4 Task 14 — the family playlists are the hub's list, cached.
                FamilyPlaylistsCacheKey,
                // SARASWATI: Phase 4 Task 12 — this phone's own top-songs playlist and the time of its last sync.
                ParivaarPlaylistIdKey,
                ParivaarTopSongsSyncedAtKey,
                // SARASWATI: Phase 4 review fix (6) — "this phone is a remote controller" is live room state; a restored
                // `true` would bring the phone up silent in the Sangat, a restored absence would let it play.
                RemoteControllerStore.Key,
                // SARASWATI: final fix wave (I3) — the car room this phone hosts is live room state, like the flag above.
                SangatCarRoomCodeKey,
                // SARASWATI: Task 7/8 fix round 1 — this phone's own hub state,
                // none of it a credential by name but all of it identity. A
                // restore must not plant or clear the owner flag (Manage
                // Family's gate), must not swap the hub device id out from
                // under the live session token (every health report and the
                // hub's own checks pair the two), and the "stations were
                // saved" marker decides whether an empty hub list shows the
                // starting defaults. mergeRestoreOntoLive keeps this phone's
                // live values for all three.
                IsFamilyOwnerKey,
                FamilyDeviceIdKey,
                FamilyStationsSavedKey,
                // SARASWATI: fix wave B2 — the running count of 401s against this phone's
                // session belongs to the live session only; a restored count could unpair
                // a phone early, a restored absence would hand a failing one a fresh start.
                FamilyUnauthorizedCountKey,
                FamilyUnauthorizedAtKey,
            )

    // Preference keys the coverage test's wider regex flags as
    // sensitive-shaped, but which sanitize() deliberately keeps. Kept as a
    // Map (not a Set) even with one entry so a future addition follows the
    // same "key to reason" shape as SecretPreferenceKeys.NOT_SECRET.
    val SAFE: Map<Preferences.Key<*>, String> =
        mapOf(
            // SARASWATI: Preferences.Key<T> declares its own `to(value: T)` returning a
            // Preferences.Pair, which shadows kotlin.to() whenever the reason string's type
            // happens to match T — this key's value type is Long, so kotlin.to() still wins
            // here, but the Pair(...) constructor is used anyway for consistency with
            // NEVER_EXPORT_OR_RESTORE's own fix-round-1 history (see git log on this file).
            Pair(
                ListenTogetherSessionTimestampKey,
                "paired with ListenTogetherSessionTokenKey, which is already in " +
                    "SecretPreferenceKeys.all above; a bare timestamp carries no " +
                    "credential or address by itself (same reasoning as " +
                    "SecretPreferenceKeys.NOT_SECRET)",
            ),
        )

    /** Returns a copy of [prefs] with every [NEVER_EXPORT_OR_RESTORE] key removed. */
    fun sanitize(prefs: Preferences): Preferences {
        val mutable = prefs.toMutablePreferences()
        for (key in NEVER_EXPORT_OR_RESTORE) {
            mutable.removeAny(key)
        }
        return mutable.toPreferences()
    }

    // SARASWATI (I1): restoring a backup must never touch the live sealed
    // cookie, VisitorData, DataSyncId or signed-in account identity —
    // Task 16's own comment said so, but `prefs.clear(); prefs += restored`
    // actually wiped them, because `restored` has already gone through
    // sanitize() and therefore contains NONE of these keys at all. This
    // function is the one place that decides what "preserve across a
    // restore" means, so both BackupRestoreViewModel.restore() and its
    // test use the exact same logic: every NEVER_EXPORT_OR_RESTORE key
    // keeps EXACTLY its current live value — present or absent — no
    // matter what [restored] does or doesn't contain for it. Every other
    // key comes from [restored] as-is; that is the entire point of a
    // restore.
    fun mergeRestoreOntoLive(live: Preferences, restored: Preferences): Preferences {
        val merged = restored.toMutablePreferences()
        for (key in NEVER_EXPORT_OR_RESTORE) {
            merged.removeAny(key)
            copyLiveValue(live, merged, key)
        }
        return merged.toPreferences()
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.removeAny(key: Preferences.Key<*>) {
        // Preferences.Key<T> is invariant, but MutablePreferences.remove()
        // only ever uses the key's string name to look up the backing map
        // entry (see Preferences.Key.equals/hashCode) — the type parameter
        // is a read-side convenience, not something removal depends on, so
        // this cast is safe regardless of the key's real T. Same pattern as
        // SecretScrubber.removeAny.
        remove(key as Preferences.Key<Any>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyLiveValue(
        live: Preferences,
        into: MutablePreferences,
        key: Preferences.Key<*>,
    ) {
        // Same invariance note as removeAny above — the cast is safe
        // because Preferences' get/set only ever key off the name.
        val typedKey = key as Preferences.Key<Any>
        val value = live[typedKey] ?: return
        into[typedKey] = value
    }

    // Size caps for every entry a backup ZIP or import file can carry.
    // readLimited() below enforces them without ever buffering more than
    // one byte past the cap (audit A10-4: "no caps on readBytes()/
    // readLines(), all previewed on the main thread" — the thread part is
    // fixed at the call sites in BackupRestoreViewModel/BackupAndRestore).
    const val MAX_SETTINGS_BYTES = 1_048_576L
    const val MAX_ALIASES_BYTES = 1_048_576L
    const val MAX_DB_BYTES = 536_870_912L
    const val MAX_IMPORT_TEXT_BYTES = 5_242_880L

    private const val READ_CHUNK_SIZE = 8192

    /**
     * Reads [input] fully, throwing [BackupTooLargeException] the moment more than
     * [maxBytes] would have been read. Never reads more than `maxBytes + 1` bytes from
     * [input] — enough to detect "too large" without ever buffering an attacker-controlled
     * amount of data first.
     */
    fun readLimited(
        input: InputStream,
        maxBytes: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_SIZE)
        var total = 0L
        while (total <= maxBytes) {
            val remaining = maxBytes + 1 - total
            val toRead = minOf(chunk.size.toLong(), remaining).toInt()
            if (toRead <= 0) break
            val n = input.read(chunk, 0, toRead)
            if (n < 0) break
            out.write(chunk, 0, n)
            total += n
        }
        if (total > maxBytes) throw BackupTooLargeException()
        return out.toByteArray()
    }
}

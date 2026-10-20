/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — whether this phone is signed in to YouTube,
// which decides whether Saral shows its sign-in tile. It is the test the rest
// of the app already uses (SyncUtils, the Home, History and Stats screens): the
// sign-in cookie, opened with the vault, holds a SAPISID. There is no second
// notion of being signed in.
package com.metrolist.music.saraswati.saral

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.saraswati.security.CookieVault
import com.metrolist.music.saraswati.security.OpenResult
import com.metrolist.music.saraswati.security.SealedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

interface YouTubeSignIn {
    val signedIn: Flow<Boolean>
}

/** The app's own signed-in test, on an already opened cookie. */
fun isYouTubeSignedIn(cookie: String?): Boolean = cookie?.let { "SAPISID" in parseCookieString(it) } ?: false

class DataStoreYouTubeSignIn(
    dataStore: DataStore<Preferences>,
    vault: CookieVault,
    io: CoroutineDispatcher = Dispatchers.IO,
) : YouTubeSignIn {
    // A storage error ends the flow without an answer; the view model keeps assuming signed in, so
    // an unreadable store never puts a sign-in tile in front of someone who is signed in.
    //
    // SARASWATI: fix wave A (I1, I4) — only a change to the cookie itself is decrypted (the raw
    // value is compared first, so an unrelated preference changing costs nothing), the decrypt runs
    // on [io], and a Keystore that is unavailable gives no answer at all rather than "signed out":
    // the next change to the cookie asks again.
    override val signedIn: Flow<Boolean> =
        dataStore.data
            .map { it[InnerTubeCookieKey] }
            .distinctUntilChanged()
            .mapNotNull { stored -> signedInOrNull(stored, vault) }
            .distinctUntilChanged()
            .flowOn(io)
            .catch { }

    private fun signedInOrNull(
        stored: String?,
        vault: CookieVault,
    ): Boolean? {
        if (stored == null) return false
        return when (val opened = vault.open(stored, SealedPreferences.purposeOf(InnerTubeCookieKey))) {
            is OpenResult.Opened -> isYouTubeSignedIn(opened.plaintext)
            OpenResult.Rejected -> false
            OpenResult.Unavailable -> null
        }
    }
}

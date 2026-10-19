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
import com.metrolist.music.saraswati.security.SealedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface YouTubeSignIn {
    val signedIn: Flow<Boolean>
}

/** The app's own signed-in test, on an already opened cookie. */
fun isYouTubeSignedIn(cookie: String?): Boolean = cookie?.let { "SAPISID" in parseCookieString(it) } ?: false

class DataStoreYouTubeSignIn(
    dataStore: DataStore<Preferences>,
    vault: CookieVault,
) : YouTubeSignIn {
    // A storage error ends the flow without an answer; the view model keeps assuming signed in, so
    // an unreadable store never puts a sign-in tile in front of someone who is signed in.
    override val signedIn: Flow<Boolean> =
        dataStore.data
            .map { isYouTubeSignedIn(SealedPreferences.unseal(InnerTubeCookieKey, it[InnerTubeCookieKey], vault)) }
            .distinctUntilChanged()
            .catch { }
}

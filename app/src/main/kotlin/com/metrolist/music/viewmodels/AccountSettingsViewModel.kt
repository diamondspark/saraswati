/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import com.metrolist.music.App
import com.metrolist.music.utils.SyncUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val syncUtils: SyncUtils,
) : ViewModel() {

    /**
     * Clear all library data including songs, albums, artists, playlists, podcasts.
     */
    suspend fun clearAllLibraryData() {
        Timber.d("[LOGOUT_CLEAR] ViewModel: clearAllLibraryData called")
        syncUtils.clearAllLibraryData()
        Timber.d("[LOGOUT_CLEAR] ViewModel: clearAllLibraryData completed")
    }

    /**
     * Forget the account FIRST (clearing auth so all background syncs skip),
     * THEN clear all library data. This prevents sync operations that are
     * triggered by the database becoming empty from re-adding songs.
     */
    suspend fun logoutAndClearLibraryData(context: Context) {
        Timber.d("[LOGOUT_CLEAR] ViewModel: logoutAndClearLibraryData called")
        withContext(Dispatchers.IO) {
            // Forget account first — clears cookie/auth from DataStore.
            // Once isLoggedIn() returns false, ALL sync operations will skip.
            App.forgetAccount(context)

            // Now clear the local database. Any sync coroutines that observe
            // the empty state will check isLoggedIn() and skip silently.
            syncUtils.clearAllLibraryData()
        }
        Timber.d("[LOGOUT_CLEAR] ViewModel: logoutAndClearLibraryData completed")
    }

    /**
     * Just logout without clearing library data
     */
    suspend fun logoutKeepData(context: Context, onCookieChange: (String) -> Unit) {
        Timber.d("[LOGOUT_KEEP] ViewModel: logoutKeepData called")
        withContext(Dispatchers.IO) {
            App.forgetAccount(context)
        }
        Timber.d("[LOGOUT_KEEP] ViewModel: Account forgotten, clearing cookie in UI")
        onCookieChange("")
    }

    // SARASWATI: the raw-cookie token editor this method served (`saveTokenAndRestart`) is
    // removed entirely (audit A6.4/A11-4, ruling R18) — Saraswati signs in only through the
    // login WebView, so nothing in the app accepts a pasted cookie/visitor-data/data-sync-id
    // any more. See docs/strip-checklist.md and AccountSettings.kt.
}

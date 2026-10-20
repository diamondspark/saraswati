/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.metrolist.music.R

@Immutable
sealed class Screens(
    @StringRes val titleId: Int,
    @DrawableRes val iconIdInactive: Int,
    @DrawableRes val iconIdActive: Int,
    val route: String,
) {
    object Home : Screens(
        titleId = R.string.home,
        iconIdInactive = R.drawable.home_outlined,
        iconIdActive = R.drawable.home_filled,
        route = "home"
    )

    object Search : Screens(
        titleId = R.string.search,
        iconIdInactive = R.drawable.search,
        iconIdActive = R.drawable.search,
        route = "search_input"
    )

    object ListenTogether : Screens(
        titleId = R.string.together,
        iconIdInactive = R.drawable.group_outlined,
        iconIdActive = R.drawable.group_filled,
        route = "listen_together"
    )

    object Library : Screens(
        titleId = R.string.filter_library,
        iconIdInactive = R.drawable.library_music_outlined,
        iconIdActive = R.drawable.library_music_filled,
        route = "library"
    )

    // SARASWATI: Task 7 — the Family tab (Home · Search · Library · Family),
    // shaped exactly like ListenTogether above.
    object Family : Screens(
        titleId = R.string.family,
        iconIdInactive = R.drawable.group_outlined,
        iconIdActive = R.drawable.group_filled,
        route = "family"
    )

    // SARASWATI: Phase 4 Task 19 — a car passenger's remote-controller screen. Not a tab, so not in MainScreens.
    object SangatRemote : Screens(
        titleId = R.string.sangat_remote_title,
        iconIdInactive = R.drawable.group_outlined,
        iconIdActive = R.drawable.group_filled,
        route = "sangat/remote",
    )

    // SARASWATI: Phase 4 Task 21 — Import from Spotify, opened from the Library menu. Not a tab.
    object SpotifyImport : Screens(
        titleId = R.string.spotify_import_title,
        iconIdInactive = R.drawable.upload,
        iconIdActive = R.drawable.upload,
        route = "spotify_import",
    )

    companion object {
        val MainScreens = listOf(Home, Search, ListenTogether, Library, Family)
    }
}

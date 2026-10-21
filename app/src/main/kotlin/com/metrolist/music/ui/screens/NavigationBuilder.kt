/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.app.Activity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.navArgument
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.saraswati.family.FamilyRoutes
import com.metrolist.music.saraswati.family.FamilyScreen
import com.metrolist.music.saraswati.family.feedback.FeedbackInboxScreen
import com.metrolist.music.saraswati.family.feedback.FeedbackScreen
import com.metrolist.music.saraswati.family.parivaar.TasteHistoryScreen
import com.metrolist.music.saraswati.localmedia.LOCAL_SONGS_ROUTE
import com.metrolist.music.saraswati.localmedia.LocalSongsScreen
import com.metrolist.music.saraswati.family.playlists.FamilyPlaylistScreen
import com.metrolist.music.saraswati.family.JoinFamilyScreen
import com.metrolist.music.saraswati.family.sangat.RemoteControllerScreen
import com.metrolist.music.saraswati.spotifyimport.SpotifyImportScreen
import com.metrolist.music.saraswati.craft.snooze.SnoozedSongsScreen
import com.metrolist.music.saraswati.family.ManageFamilyScreen
import com.metrolist.music.ui.screens.artist.ArtistAlbumsScreen
import com.metrolist.music.ui.screens.artist.ArtistItemsScreen
import com.metrolist.music.ui.screens.artist.ArtistScreen
import com.metrolist.music.ui.screens.artist.ArtistSongsScreen
import com.metrolist.music.ui.screens.equalizer.EqScreen
import com.metrolist.music.ui.screens.equalizer.wizard.WizardScreen
import com.metrolist.music.ui.screens.library.LibraryScreen
import com.metrolist.music.ui.screens.playlist.AutoPlaylistScreen
import com.metrolist.music.ui.screens.playlist.CachePlaylistScreen
import com.metrolist.music.ui.screens.playlist.LocalPlaylistScreen
import com.metrolist.music.ui.screens.playlist.OnlinePlaylistScreen
import com.metrolist.music.ui.screens.playlist.TopPlaylistScreen
import com.metrolist.music.ui.screens.podcast.OnlinePodcastScreen
import com.metrolist.music.ui.screens.recognition.RecognitionHistoryScreen
import com.metrolist.music.ui.screens.recognition.RecognitionScreen
import com.metrolist.music.ui.screens.search.OnlineSearchResult
import com.metrolist.music.ui.screens.search.SearchScreen
import com.metrolist.music.ui.screens.settings.AboutScreen
import com.metrolist.music.ui.screens.settings.AiSettings
import com.metrolist.music.ui.screens.settings.AndroidAutoSettings
import com.metrolist.music.ui.screens.settings.AppearanceSettings
import com.metrolist.music.ui.screens.settings.BackupAndRestore
import com.metrolist.music.ui.screens.settings.ContentSettings
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.ui.screens.settings.PlayerSettings
import com.metrolist.music.ui.screens.settings.PrivacySettings
import com.metrolist.music.ui.screens.settings.RomanizationSettings
import com.metrolist.music.ui.screens.settings.SettingsScreen
import com.metrolist.music.ui.screens.settings.StorageSettings
import com.metrolist.music.ui.screens.settings.ThemeScreen
import com.metrolist.music.ui.screens.settings.UpdaterScreen
import com.metrolist.music.ui.screens.settings.integrations.ListenTogetherSettings

import com.metrolist.music.ui.screens.wrapped.WrappedScreen
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.navigationBuilder(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
    latestVersionName: String,
    activity: Activity,
    snackbarHostState: SnackbarHostState,
) {
    composable(Screens.Home.route) {
        HomeScreen(snackbarHostState = snackbarHostState)
    }

    composable(Screens.Search.route) { backStackEntry ->
        val pureBlackEnabled by rememberPreference(PureBlackKey, defaultValue = false)
        val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
        val isSystemInDarkTheme = isSystemInDarkTheme()
        val useDarkTheme =
            remember(darkTheme, isSystemInDarkTheme) {
                if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            }
        val pureBlack =
            remember(pureBlackEnabled, useDarkTheme) {
                pureBlackEnabled && useDarkTheme
            }
        SearchScreen(
            pureBlack = pureBlack,
            savedStateHandle = backStackEntry.savedStateHandle
        )
    }

    composable(Screens.Library.route) {
        LibraryScreen()
    }

    composable(Screens.ListenTogether.route) {
        ListenTogetherScreen(navController, showTopBar = false)
    }

    // SARASWATI: Task 7 — Family tab, Manage Family and the pairing join flow.
    composable(Screens.Family.route) {
        FamilyScreen(navController)
    }

    composable(FamilyRoutes.MANAGE) {
        ManageFamilyScreen(navController)
    }

    composable(FamilyRoutes.JOIN) {
        JoinFamilyScreen(navController)
    }

    // SARASWATI: feedback — the owner's Feedback inbox.
    composable(FamilyRoutes.FEEDBACK) {
        FeedbackInboxScreen(navController)
    }

    // SARASWATI: Phase 4 Task 21 — Import from Spotify, reached from the Library menu.
    composable(Screens.SpotifyImport.route) {
        SpotifyImportScreen(navController)
    }

    // SARASWATI: Phase 6 Task 3 — the Library menu's "Snoozed songs" list.
    composable(Screens.Snoozed.route) {
        SnoozedSongsScreen(navController)
    }

    // SARASWATI: Phase 4 Task 19 — a car passenger's remote-controller screen.
    composable(Screens.SangatRemote.route) {
        RemoteControllerScreen(navController)
    }

    // SARASWATI: Phase 4 Task 20 — Taste & history.
    composable(FamilyRoutes.TASTE) {
        TasteHistoryScreen(navController)
    }

    // SARASWATI: Phase 5 Task 14 — songs on this phone (opened from the Library entry).
    composable(LOCAL_SONGS_ROUTE) {
        LocalSongsScreen(navController)
    }

    // SARASWATI: Phase 4 Task 20 — one family playlist, by its YouTube playlist id.
    composable(
        route = FamilyRoutes.PLAYLIST,
        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
    ) { backStackEntry ->
        FamilyPlaylistScreen(navController, backStackEntry.arguments?.getString("playlistId").orEmpty())
    }

    composable(
        route = "listen_together_from_topbar",
    ) {
        ListenTogetherScreen(navController, showTopBar = true)
    }

    composable("history") {
        HistoryScreen(navController)
    }

    composable("stats") {
        StatsScreen(navController)
    }

    composable("mood_and_genres") {
        MoodAndGenresScreen(navController)
    }

    composable("account") {
        AccountScreen(navController)
    }

    composable("new_release") {
        NewReleaseScreen(navController)
    }

    composable("charts_screen") {
        ChartsScreen(navController)
    }

    composable(
        route = "browse/{browseId}",
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                },
            ),
    ) {
        BrowseScreen(
            navController,
            it.arguments?.getString("browseId"),
        )
    }

    composable(
        route = "search/{query}",
        arguments =
            listOf(
                navArgument("query") {
                    type = NavType.StringType
                },
            ),
        enterTransition = {
            fadeIn(tween(250))
        },
        exitTransition = {
            if (targetState.destination.route?.startsWith("search/") == true) {
                fadeOut(tween(200))
            } else {
                fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
            }
        },
        popEnterTransition = {
            if (initialState.destination.route?.startsWith("search/") == true) {
                fadeIn(tween(250))
            } else {
                fadeIn(tween(250)) + slideInHorizontally { -it / 2 }
            }
        },
        popExitTransition = {
            fadeOut(tween(200))
        },
    ) { backStackEntry ->
        OnlineSearchResult(
            savedStateHandle = backStackEntry.savedStateHandle
        )

    }

    composable(
        route = "album/{albumId}",
        arguments =
            listOf(
                navArgument("albumId") {
                    type = NavType.StringType
                },
            ),
    ) {
        AlbumScreen(navController)
    }

    composable(
        route = "artist/{artistId}?isPodcastChannel={isPodcastChannel}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
                navArgument("isPodcastChannel") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
    ) {
        ArtistScreen(navController)
    }

    composable(
        route = "artist/{artistId}/songs",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistSongsScreen(navController)
    }

    composable(
        route = "artist/{artistId}/albums",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistAlbumsScreen(navController, scrollBehavior)
    }

    composable(
        route = "artist/{artistId}/items?browseId={browseId}?params={params}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        ArtistItemsScreen(navController)
    }

    composable(
        route = "online_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        OnlinePlaylistScreen(navController)
    }

    composable(
        route = "online_podcast/{podcastId}",
        arguments =
            listOf(
                navArgument("podcastId") {
                    type = NavType.StringType
                },
            ),
    ) {
        OnlinePodcastScreen(navController, scrollBehavior)
    }

    composable(
        route = "local_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        LocalPlaylistScreen(navController)
    }

    composable(
        route = "auto_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
            ),
    ) {
        AutoPlaylistScreen(navController)
    }

    composable(
        route = "cache_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
            ),
    ) {
        CachePlaylistScreen(navController)
    }

    composable(
        route = "top_playlist/{top}",
        arguments =
            listOf(
                navArgument("top") {
                    type = NavType.StringType
                },
            ),
    ) {
        TopPlaylistScreen(navController)
    }

    composable(
        route = "youtube_browse/{browseId}?params={params}",
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        YouTubeBrowseScreen(navController)
    }

    composable("settings") {
        SettingsScreen(navController, latestVersionName)
    }

    composable("settings/appearance") {
        AppearanceSettings(navController, snackbarHostState)
    }

    composable("settings/appearance/theme") {
        ThemeScreen(navController)
    }

    composable("settings/content") {
        ContentSettings(navController)
    }

    composable("settings/content/romanization") {
        RomanizationSettings(navController)
    }

    composable("settings/ai") {
        AiSettings(navController)
    }

    composable("settings/player") {
        PlayerSettings(navController)
    }

    composable("settings/storage") {
        StorageSettings(navController)
    }

    composable("settings/privacy") {
        PrivacySettings(navController)
    }

    composable("settings/backup_restore") {
        BackupAndRestore(navController)
    }

    // SARASWATI: Discord and Last.fm removed; the Integrations hub and their settings routes went with them.
    composable(route = "settings/integrations/listen_together") {
        ListenTogetherSettings(navController)
    }

    composable("settings/updater") {
        UpdaterScreen(navController)
    }

    // SARASWATI: feedback — Settings, System, "Send feedback".
    composable("settings/feedback") {
        FeedbackScreen(navController)
    }

    composable("settings/about") {
        AboutScreen(navController)
    }

    composable("login") {
        LoginScreen(navController)
    }

    composable("switch_channel") {
        LoginScreen(
            navController = navController,
            isSwitchingChannel = true,
        )
    }

    composable("wrapped") {
        WrappedScreen()
    }

    composable("equalizer") {
        EqScreen()
    }

    composable("eq_wizard") {
        WizardScreen(onNavigateBack = {
            navController.popBackStack()
        })
    }

    composable(
        route = "recognition?autoStart={autoStart}",
        arguments =
            listOf(
                navArgument("autoStart") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
    ) {
        RecognitionScreen(navController, it.arguments?.getBoolean("autoStart") ?: false)
    }

    composable("recognition_history") {
        RecognitionHistoryScreen(navController)
    }
    composable("settings/android_auto") {
        AndroidAutoSettings(navController)
    }
}

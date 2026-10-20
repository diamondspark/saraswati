/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 20 — one family playlist: its name, who made it on this family's phones, the read-only
// line for phones that did not make it, and buttons to play it or open it in YouTube Music's own playlist screen.
// Songs are shown there, not copied here, so the list stays what YouTube says it is.
package com.metrolist.music.saraswati.family.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.playback.queues.YouTubeQueue

@Composable
fun FamilyPlaylistScreen(
    navController: NavController,
    playlistId: String,
    viewModel: FamilyPlaylistsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val madeHereIds by viewModel.madeHere.collectAsStateWithLifecycle()
    val playerConnection = LocalPlayerConnection.current
    val playlist = playlists.firstOrNull { it.playlistId == playlistId }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (playlist == null) {
            Text(
                stringResource(R.string.family_playlist_not_found),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }
        val madeHere = playlist.id in madeHereIds
        Text(stringResource(R.string.family_playlist_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(playlist.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(if (madeHere) R.string.family_playlist_by_you else R.string.family_playlist_by_other),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(if (madeHere) R.string.family_playlist_yours_note else R.string.family_playlist_read_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = { playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(playlistId = playlist.playlistId))) },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.family_playlist_play))
        }
        OutlinedButton(
            onClick = { navController.navigate("online_playlist/${playlist.playlistId}") },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.family_playlist_open))
        }
    }
}

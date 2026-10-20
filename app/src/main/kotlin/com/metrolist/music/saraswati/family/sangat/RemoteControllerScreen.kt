/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — the car passenger's remote-controller screen (route sangat/remote). It shows the
// room's track and up-next queue as the host's phone reports them. No transport controls and no host actions:
// nothing plays on this phone. Songs are added through Search, where the existing song menus suggest to the room.
package com.metrolist.music.saraswati.family.sangat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.screens.Screens

private const val UP_NEXT_LIMIT = 30

@Composable
fun RemoteControllerScreen(
    navController: NavController,
    viewModel: SangatViewModel = hiltViewModel(),
) {
    val remote by viewModel.remoteController.collectAsStateWithLifecycle()
    val now by viewModel.remoteNowPlaying.collectAsStateWithLifecycle()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sangat_remote_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.sangat_remote_nothing_plays),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        if (!remote) {
            Text(
                text = stringResource(R.string.sangat_remote_not_in_car),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { navController.popBackStack() }) {
                Text(stringResource(R.string.sangat_remote_back))
            }
            return@Column
        }

        val track = now?.track
        Text(
            text = stringResource(R.string.sangat_remote_now),
            style = MaterialTheme.typography.titleSmall,
        )
        if (track == null) {
            Text(
                text = stringResource(R.string.sangat_remote_nothing_yet),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    stringResource(
                        if (now?.isPlaying == true) R.string.sangat_remote_playing else R.string.sangat_remote_paused,
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.sangat_remote_up_next),
            style = MaterialTheme.typography.titleSmall,
        )
        val upNext = now?.queue.orEmpty().take(UP_NEXT_LIMIT)
        if (upNext.isEmpty()) {
            Text(
                text = stringResource(R.string.sangat_remote_nothing_queued),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Material3SettingsGroup(
                items =
                    upNext.map { queued ->
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.queue_music),
                            title = { Text(queued.title) },
                            description = { Text(queued.artist) },
                        )
                    },
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { navController.navigate(Screens.Search.route) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sangat_remote_add))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                viewModel.leave()
                navController.popBackStack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sangat_remote_leave))
        }
        Spacer(Modifier.height(16.dp))
    }
}

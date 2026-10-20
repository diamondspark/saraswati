/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 14 — On this phone: the entry row in Library and the screen behind it. Copy and layout
// follow the approved mockups (boards 1 to 8). The privacy line is on the screen in every state.
package com.metrolist.music.saraswati.localmedia

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.menu.SongMenu

const val LOCAL_SONGS_ROUTE = "local_songs"

@Composable
fun LocalSongsEntryRow(
    onClick: () -> Unit,
    viewModel: LocalSongsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = stringResource(R.string.local_songs_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = entrySubtitle(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.navigate_next),
            contentDescription = null,
        )
    }
}

@Composable
private fun entrySubtitle(state: LocalSongsState): String =
    when (state) {
        LocalSongsState.Unsupported -> stringResource(R.string.local_files_unsupported)
        LocalSongsState.Off -> stringResource(R.string.local_songs_off_short)
        is LocalSongsState.NeedsPermission -> stringResource(R.string.local_songs_tap_to_find)
        LocalSongsState.Scanning -> stringResource(R.string.local_songs_scanning)
        LocalSongsState.Empty -> stringResource(R.string.local_songs_empty)
        is LocalSongsState.Songs ->
            pluralStringResource(R.plurals.local_songs_count, state.list.size, state.list.size)
    }

@Composable
fun LocalSongsScreen(
    navController: NavController,
    viewModel: LocalSongsViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val requestAllow = rememberLocalAudioPermission { granted -> viewModel.onPermissionResult(granted) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
            Text(
                text = stringResource(R.string.local_songs_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (state is LocalSongsState.Songs) {
                IconButton(onClick = viewModel::refresh) {
                    Icon(
                        painter = painterResource(R.drawable.refresh),
                        contentDescription = stringResource(R.string.local_songs_refresh),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.local_songs_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )

        when (val current = state) {
            LocalSongsState.Unsupported ->
                Text(
                    text = stringResource(R.string.local_files_unsupported),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )

            LocalSongsState.Off ->
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = stringResource(R.string.local_files_desc_off),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // SARASWATI: Phase 5 fix (P5-I2) — without access, the explanation comes before the system prompt.
                    if (access == LocalAccess.GRANTED) {
                        Button(
                            onClick = { viewModel.turnOn() },
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text(stringResource(R.string.local_songs_turn_on))
                        }
                    } else {
                        LocalAccessPrompt(
                            deniedCount = 0,
                            onAllow = requestAllow,
                            onOpenSettings = { openAppSettings(context) },
                        )
                    }
                }

            is LocalSongsState.NeedsPermission ->
                LocalAccessPrompt(
                    deniedCount = current.deniedCount,
                    onAllow = requestAllow,
                    onOpenSettings = { openAppSettings(context) },
                )

            LocalSongsState.Scanning ->
                Row(
                    modifier = Modifier.padding(top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text(
                        text = stringResource(R.string.local_songs_scanning),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

            LocalSongsState.Empty ->
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = stringResource(R.string.local_songs_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.local_songs_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = viewModel::refresh,
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text(stringResource(R.string.local_songs_check_again))
                    }
                }

            is LocalSongsState.Songs ->
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item(key = "actions") {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(onClick = { viewModel.play(0) { playerConnection.playQueue(it) } }) {
                                Icon(painterResource(R.drawable.play), contentDescription = null)
                                Text(
                                    text = stringResource(R.string.local_songs_play_all),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            OutlinedButton(onClick = { viewModel.shuffle { playerConnection.playQueue(it) } }) {
                                Icon(painterResource(R.drawable.shuffle), contentDescription = null)
                                Text(
                                    text = stringResource(R.string.local_songs_shuffle),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                    itemsIndexed(
                        items = current.list,
                        key = { _, song -> song.id },
                        contentType = { _, _ -> "local_song" },
                    ) { index, song ->
                        SongListItem(
                            song = song,
                            showLikedIcon = true,
                            showDownloadIcon = false,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            SongMenu(
                                                originalSong = song,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.play(index) { playerConnection.playQueue(it) } },
                        )
                    }
                }
        }
    }
}

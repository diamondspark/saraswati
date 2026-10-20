/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 20 — the Family tab's playlist section: the family's shared playlists, a way to make a
// new one, and a row per playlist that opens its screen. Hidden while the owner has switched family playlists off.
// The name being typed is not saved across process death (remember, not rememberSaveable).
package com.metrolist.music.saraswati.family.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.saraswati.family.FamilyPlaylist
import com.metrolist.music.saraswati.family.familyPlaylistRoute
import com.metrolist.music.saraswati.family.messageRes
import kotlinx.coroutines.launch

@Composable
fun FamilyPlaylistsSection(
    navController: NavController,
    viewModel: FamilyPlaylistsViewModel,
) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (!visible) return
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.family_playlists_title), style = MaterialTheme.typography.titleMedium)
            Text(
                " " + stringResource(R.string.family_playlists_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (playlists.isEmpty()) {
            Text(
                stringResource(R.string.family_playlists_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        playlists.forEach { playlist ->
            PlaylistRow(
                playlist = playlist,
                madeHere = viewModel.canAdd(playlist),
                onClick = { navController.navigate(familyPlaylistRoute(playlist.playlistId)) },
            )
        }
        error?.let {
            Text(
                stringResource(it.messageRes()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        OutlinedButton(onClick = { showCreate = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.family_playlists_new))
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onCreate = { title ->
                scope.launch {
                    val created = viewModel.create(title)
                    if (created.isSuccess) showCreate = false
                }
            },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: FamilyPlaylist,
    madeHere: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (madeHere) R.string.family_playlist_by_you else R.string.family_playlist_by_other),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(if (madeHere) R.string.family_playlist_status_edit else R.string.family_playlist_status_read),
            style = MaterialTheme.typography.labelLarge,
            color = if (madeHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.family_playlists_new)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(FamilyPlaylistsRepository.MAX_TITLE_LENGTH) },
                    label = { Text(stringResource(R.string.family_playlists_name_label)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.family_playlists_create))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

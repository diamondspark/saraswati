/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — the Sangat section on the Family tab: start a room, join the live ones, and open
// or leave the room this phone is in. Shown to family phones only, and not at all while Sangat is off. The room
// controls (approvals, members) are the Listen Together dialog, opened from here.
package com.metrolist.music.saraswati.family.sangat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.menu.ListenTogetherDialog
import com.metrolist.music.ui.screens.Screens

@Composable
fun SangatSection(
    navController: NavController,
    viewModel: SangatViewModel = hiltViewModel(),
) {
    val on by viewModel.sangatOn.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val info by viewModel.roomInfo.collectAsStateWithLifecycle()
    val askName by viewModel.askName.collectAsStateWithLifecycle()
    var showRoom by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.enteredCarRoom.collect { navController.navigate(Screens.SangatRemote.route) }
    }

    if (!on) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stringResource(R.string.family_sangat_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = stringResource(R.string.family_sangat_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val room = info
        if (room?.isHost == true) {
            HostingCard(
                listening = (room.listening - 1).coerceAtLeast(0),
                onEnd = viewModel::leave,
                onOpenRoom = { showRoom = true },
            )
        } else if (room == null) {
            Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.family_sangat_start), fontWeight = FontWeight.Bold)
            }
        }

        if (rows.isEmpty()) {
            if (room?.isHost != true) {
                Text(
                    text = stringResource(R.string.family_sangat_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.family_sangat_live_heading).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            rows.forEach { row ->
                LiveRoomRow(
                    row = row,
                    onJoin = { viewModel.join(row.room) },
                    onLeave = viewModel::leave,
                    onOpen = {
                        if (row.room.carMode) navController.navigate(Screens.SangatRemote.route) else showRoom = true
                    },
                )
            }
        }
    }

    if (askName) {
        SangatNameDialog(onSubmit = viewModel::submitName, onDismiss = viewModel::cancelNameRequest)
    }

    ListenTogetherDialog(visible = showRoom, mediaMetadata = null, onDismiss = { showRoom = false })
}

@Composable
private fun HostingCard(
    listening: Int,
    onEnd: () -> Unit,
    onOpenRoom: () -> Unit,
) {
    OutlinedCard(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SangatLiveDot()
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.family_sangat_hosting_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.family_sangat_hosting_desc, listening),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onEnd, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(stringResource(R.string.family_sangat_end), fontWeight = FontWeight.Bold)
                }
            }
            TextButton(onClick = onOpenRoom) {
                Text(stringResource(R.string.family_sangat_open_room))
            }
        }
    }
}

@Composable
private fun LiveRoomRow(
    row: SangatRow,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onOpen: () -> Unit,
) {
    val room = row.room
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .then(if (row.joined) Modifier.clickable(onClick = onOpen) else Modifier)
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SangatAvatar(name = room.hostName, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.family_sangat_room_title, room.hostName),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (room.carMode) InCarTag()
                }
                Text(
                    text =
                        when {
                            row.joined && room.carMode -> stringResource(R.string.family_sangat_remote_here)
                            row.joined -> stringResource(R.string.family_sangat_in_sync_here)
                            else ->
                                SangatDisplay.songTitle(room)?.let { stringResource(R.string.family_sangat_listening_song, room.memberCount, it) }
                                    ?: stringResource(R.string.family_sangat_listening, room.memberCount)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.joined) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.joined) {
                FilledTonalButton(onClick = onLeave, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(stringResource(R.string.family_sangat_leave_short), fontWeight = FontWeight.Bold)
                }
            } else {
                Button(onClick = onJoin, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(stringResource(R.string.family_sangat_join), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Asks once for the name this phone goes by in a room. */
@Composable
fun SangatNameDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.family_sangat_name_title)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(onClick = { onSubmit(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.family_sangat_name_save))
            }
        },
    ) {
        Text(
            text = stringResource(R.string.family_sangat_name_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(SangatViewModel.MAX_NAME_LENGTH) },
            label = { Text(stringResource(R.string.family_sangat_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

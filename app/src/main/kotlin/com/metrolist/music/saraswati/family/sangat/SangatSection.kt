/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — the Sangat section on the Family tab: start a room, join the live ones, and open
// or leave the room this phone is in. Shown to family phones only, and not at all while Sangat is off. The room
// controls (approvals, members) are the Listen Together dialog, opened from here.
package com.metrolist.music.saraswati.family.sangat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.menu.ListenTogetherDialog
import com.metrolist.music.ui.screens.Screens

@Composable
fun SangatSection(
    navController: NavController,
    viewModel: SangatViewModel = hiltViewModel(),
) {
    val on by viewModel.sangatOn.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val here by viewModel.currentRoomCode.collectAsStateWithLifecycle()
    val askName by viewModel.askName.collectAsStateWithLifecycle()
    var showRoom by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.enteredCarRoom.collect { navController.navigate(Screens.SangatRemote.route) }
    }

    if (!on) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.family_sangat_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.family_sangat_desc),
            style = MaterialTheme.typography.bodyMedium,
        )

        val code = here
        if (code != null) {
            Material3SettingsGroup(
                items =
                    listOf(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.group_filled),
                            title = { Text(stringResource(R.string.family_sangat_in_room, code)) },
                            description = { Text(stringResource(R.string.family_sangat_in_room_desc)) },
                            onClick = { showRoom = true },
                        ),
                    ),
            )
            TextButton(onClick = viewModel::leave) {
                Text(stringResource(R.string.family_sangat_leave))
            }
        } else {
            Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.family_sangat_start))
            }
            if (live.isEmpty()) {
                Text(
                    text = stringResource(R.string.family_sangat_none),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = stringResource(R.string.family_sangat_live_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                Material3SettingsGroup(
                    items =
                        live.map { room ->
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.group_outlined),
                                title = { Text(room.hostName) },
                                description = {
                                    Text(
                                        if (room.carMode) {
                                            stringResource(R.string.family_sangat_car_line, room.roomCode)
                                        } else {
                                            stringResource(R.string.family_sangat_room_line, room.roomCode, room.memberCount)
                                        },
                                    )
                                },
                                onClick = { viewModel.join(room) },
                            )
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

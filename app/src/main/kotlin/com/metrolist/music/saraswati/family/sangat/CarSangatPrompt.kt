/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — the passenger's prompt "<name> started Sangat in the car", on Home. Join makes this
// phone a remote controller; Not now dismisses that room's prompt for the rest of the session.
package com.metrolist.music.saraswati.family.sangat

import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.saraswati.family.LiveSangatRoom
import com.metrolist.music.ui.component.DefaultDialog

@Composable
fun CarSangatPrompt(
    room: LiveSangatRoom,
    onJoin: () -> Unit,
    onNotNow: () -> Unit,
) {
    DefaultDialog(
        onDismiss = onNotNow,
        icon = {
            Icon(
                painter = painterResource(R.drawable.group_add),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.family_sangat_car_prompt_title, room.hostName)) },
        buttons = {
            TextButton(onClick = onNotNow) {
                Text(stringResource(R.string.family_sangat_car_prompt_not_now))
            }
            Button(onClick = onJoin) {
                Text(stringResource(R.string.family_sangat_car_prompt_join))
            }
        },
    ) {
        Text(stringResource(R.string.family_sangat_car_prompt_desc))
    }
}

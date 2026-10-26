/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — the passenger's prompt "<name> started Sangat in the car", on Home, as the approved
// mockup's bottom-sheet prompt. Join makes this phone a remote controller; Not now dismisses that room's prompt for
// the rest of the session and Home says where to join later, with Show again.
package com.metrolist.music.saraswati.family.sangat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.saraswati.family.LiveSangatRoom

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarSangatPrompt(
    room: LiveSangatRoom,
    onJoin: () -> Unit,
    onNotNow: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onNotNow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.group_add),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text =
                            if (room.carMode) {
                                stringResource(R.string.family_sangat_car_prompt_title, room.hostName)
                            } else {
                                stringResource(R.string.family_sangat_home_prompt_title, room.hostName)
                            },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text =
                            if (room.carMode) {
                                stringResource(R.string.family_sangat_car_prompt_hindi, room.hostName)
                            } else {
                                stringResource(R.string.family_sangat_home_prompt_hindi, room.hostName)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Text(
                text =
                    if (room.carMode) {
                        stringResource(R.string.family_sangat_car_prompt_desc)
                    } else {
                        stringResource(R.string.family_sangat_home_prompt_desc)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onJoin, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.family_sangat_car_prompt_join), fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.family_sangat_car_prompt_not_now))
                }
            }
        }
    }
}

/** "Okay. You can join later from Family · Sangat." with Show again, shown on Home after Not now. */
@Composable
fun CarSangatDismissedNote(
    onShowAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.family_sangat_car_prompt_dismissed),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShowAgain, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.family_sangat_car_prompt_show_again), fontWeight = FontWeight.Bold)
            }
        }
    }
}

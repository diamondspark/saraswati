/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 20 — Taste & history: the switch that lets Sangat and Parivaar Mix plays count toward this
// person's taste (CountSharedPlaysKey, off by default), and what counts under each setting. Copy and layout follow
// the approved Taste mockup.
package com.metrolist.music.saraswati.family.parivaar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R

@Composable
fun TasteHistoryScreen(
    navController: NavController,
    viewModel: ParivaarViewModel = hiltViewModel(),
) {
    val countShared by viewModel.countSharedPlays.collectAsStateWithLifecycle()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
            Text(stringResource(R.string.taste_title), style = MaterialTheme.typography.titleLarge)
        }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.parivaar_taste_title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = countShared,
                        onCheckedChange = { viewModel.setCountSharedPlays(it) },
                    )
                }
                Text(
                    stringResource(if (countShared) R.string.taste_explain_on else R.string.taste_explain_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(stringResource(R.string.taste_what_counts), style = MaterialTheme.typography.titleMedium)
            Text(
                " " + stringResource(R.string.taste_what_counts_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                CountRow(stringResource(R.string.taste_row_own), stringResource(R.string.taste_note_always), counts = true)
                CountRow(stringResource(R.string.taste_row_own_sangat), stringResource(R.string.taste_note_always), counts = true)
                CountRow(
                    stringResource(R.string.taste_row_others_sangat),
                    stringResource(if (countShared) R.string.taste_note_counted else R.string.taste_note_not_counted),
                    counts = countShared,
                )
                CountRow(
                    stringResource(R.string.taste_row_parivaar),
                    stringResource(if (countShared) R.string.taste_note_counted else R.string.taste_note_not_counted),
                    counts = countShared,
                    last = true,
                )
            }
        }

        Text(
            stringResource(R.string.taste_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CountRow(
    label: String,
    note: String,
    counts: Boolean,
    last: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(if (counts) R.string.taste_chip_counts else R.string.taste_chip_not_counted),
            style = MaterialTheme.typography.labelLarge,
            color = if (counts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!last) {
        HorizontalDivider()
    }
}

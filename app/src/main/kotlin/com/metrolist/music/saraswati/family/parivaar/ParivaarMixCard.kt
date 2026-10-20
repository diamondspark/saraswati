/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 20 — the Parivaar Mix card, used by the Family tab and by Home (copy and layout from the
// approved mockups). Hidden when the owner has switched the mix off; says what is missing while it needs two family
// members; plays the mix otherwise. The section heading is the caller's, so each screen can place it.
package com.metrolist.music.saraswati.family.parivaar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

@Composable
fun ParivaarMixCard(
    state: ParivaarUiState,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is ParivaarUiState.Hidden) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        when (state) {
            ParivaarUiState.NeedsTwoMembers ->
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.parivaar_mix_needs_two),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

            is ParivaarUiState.Ready ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.parivaar_mix_today),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.parivaar_mix_count, state.tracks.size, state.tracks.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onPlay) {
                        Text(stringResource(R.string.parivaar_mix_play))
                    }
                }

            ParivaarUiState.Hidden -> Unit
        }
    }
}

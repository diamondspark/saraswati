/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 8 — the Prahar card on Home, to the approved mockup: the window's name, one line about it, and
// Play. Shown only for the current window's mix (the caller hides it otherwise). The section heading is the caller's.
package com.metrolist.music.saraswati.craft.prahar

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

@Composable
fun PraharCard(
    mix: PraharMix,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.prahar_card_title, mix.prahar.hindi),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(blurbFor(mix.prahar)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onPlay) {
                Text(stringResource(R.string.prahar_card_play))
            }
        }
    }
}

internal fun blurbFor(prahar: Prahar): Int =
    when (prahar) {
        Prahar.USHA -> R.string.prahar_blurb_usha
        Prahar.PRATAH -> R.string.prahar_blurb_pratah
        Prahar.PURVAHNA -> R.string.prahar_blurb_purvahna
        Prahar.MADHYAHNA -> R.string.prahar_blurb_madhyahna
        Prahar.APARAHNA -> R.string.prahar_blurb_aparahna
        Prahar.SANDHYA -> R.string.prahar_blurb_sandhya
        Prahar.PRADOSH -> R.string.prahar_blurb_pradosh
        Prahar.NISHITH -> R.string.prahar_blurb_nishith
    }

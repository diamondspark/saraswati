/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the "Sudhaar" ("सुधार", Hindi for "fix/improvement")
// Home banner. Follows this fork's Hindi-name-plus-English-subtitle
// pattern (Sangat, Parivaar Mix, Saral, Veena — see
// docs/superpowers/specs/2026-10-17-saraswati-design.md): the Hindi name is
// the title, the English subtitle underneath is the brief's exact copy —
// "Update ready", or "Fixes music playback" in the error color when the
// manifest marked the release urgent (a security/playback fix, not a
// routine update).
package com.metrolist.music.saraswati.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

@Composable
fun UpdateBanner(
    status: UpdateStatus.Ready,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitleColor = if (status.urgent) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.update),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "सुधार",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (status.urgent) "Fixes music playback" else "Update ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor,
                )
            }

            Spacer(Modifier.width(12.dp))

            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

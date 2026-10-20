/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 15 — the Parivaar Saar card: the anthem with its radio, each member's favourite, the songs
// this phone shares with a chosen member (one chip at a time, tapping it again clears it), what is new this week,
// and a link to Manage Family.
package com.metrolist.music.saraswati.family.recap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun FamilyRecapCard(
    recap: FamilyRecap,
    onPlayAnthem: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.family_recap_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.family_recap_week, recap.weekOf.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section(stringResource(R.string.family_recap_anthem_label)) {
                val anthem = recap.anthem
                if (anthem == null) {
                    Text(stringResource(R.string.family_recap_anthem_none), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(anthem.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                pluralStringResource(R.plurals.family_recap_lists, recap.anthemMembers, recap.anthemMembers),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = onPlayAnthem) { Text(stringResource(R.string.family_recap_anthem_play)) }
                    }
                }
            }

            if (recap.favourites.isNotEmpty()) {
                Section(stringResource(R.string.family_recap_favourites)) {
                    recap.favourites.forEach { favourite ->
                        Text("${favourite.memberName} · ${favourite.track.title}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (recap.sharedWithMe.isNotEmpty()) {
                Section(stringResource(R.string.family_recap_shared_title)) {
                    SharedWithMe(recap)
                }
            }

            if (recap.newThisWeek.isNotEmpty()) {
                Section(stringResource(R.string.family_recap_new_title)) {
                    recap.newThisWeek.forEach { new ->
                        Text(
                            "${new.memberName} · ${new.tracks.joinToString { it.title }}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            TextButton(onClick = onManage, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.family_recap_manage))
            }
        }
    }
}

@Composable
private fun Section(
    label: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text(label, style = MaterialTheme.typography.labelLarge)
    content()
}

@Composable
private fun SharedWithMe(recap: FamilyRecap) {
    var selected by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        recap.sharedWithMe.forEach { entry ->
            FilterChip(
                selected = entry.memberName == selected,
                onClick = { selected = if (selected == entry.memberName) null else entry.memberName },
                label = { Text(entry.memberName) },
            )
        }
    }
    val shared = recap.sharedWithMe.firstOrNull { it.memberName == selected }
    if (shared != null) {
        Text(
            pluralStringResource(R.plurals.family_recap_shared_count, shared.count, shared.memberName, shared.count),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

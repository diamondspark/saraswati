/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — the "Sangat live" banner on Home, in the slot Phase 3 left above the festival banner.
// It shows while a family room is live (Join) and while this phone is in one (Leave, and who it plays in sync with).
package com.metrolist.music.saraswati.family.sangat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

@Composable
fun SangatLiveBanner(
    banner: SangatBanner,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val room = banner.room
    OutlinedCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.group_filled),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SangatLiveDot()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.family_sangat_live_title, room.hostName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text =
                        if (banner.joined && banner.remote) {
                            stringResource(R.string.family_sangat_remote_here)
                        } else if (banner.joined && banner.syncedWith.isNotEmpty()) {
                            stringResource(R.string.family_sangat_live_sync, banner.syncedWith.joinToString(", "))
                        } else {
                            SangatDisplay.songTitle(room)?.let { stringResource(R.string.family_sangat_listening_song, room.memberCount, it) }
                                ?: stringResource(R.string.family_sangat_listening, room.memberCount)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (banner.joined) {
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

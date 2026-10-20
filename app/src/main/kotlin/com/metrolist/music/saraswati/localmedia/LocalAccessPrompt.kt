/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 14 — the permission step of On this phone. The system dialog is launched only from the
// Allow button; after two refusals the button opens this app's settings instead, because the system stops asking.
package com.metrolist.music.saraswati.localmedia

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

const val LOCAL_SETTINGS_REFUSALS = 2

@Composable
fun rememberLocalAudioPermission(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return { launcher.launch(Manifest.permission.READ_MEDIA_AUDIO) }
}

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

@Composable
fun LocalAccessPrompt(
    deniedCount: Int,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val refusedTwice = deniedCount >= LOCAL_SETTINGS_REFUSALS
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.local_songs_needs_permission),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (refusedTwice) {
            Text(
                text = stringResource(R.string.local_files_refused),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Button(
            onClick = if (refusedTwice) onOpenSettings else onAllow,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(
                text = stringResource(if (refusedTwice) R.string.local_songs_open_settings else R.string.local_songs_allow),
            )
        }
    }
}

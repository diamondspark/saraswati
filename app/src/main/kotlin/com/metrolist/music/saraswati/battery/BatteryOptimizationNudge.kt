/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: battery-optimization nudge — aggressive OEM power management (Samsung, Xiaomi) can stop background
// playback once the screen locks. The fix is a per-app setting only the user can change, so this prompt only points
// there. It is shown from MetrolistApp (MainActivity) and dismissed once, persisted via BatteryOptimizationNudgeDismissedKey.
package com.metrolist.music.saraswati.battery

import android.content.Context
import android.os.PowerManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R

fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@Composable
fun BatteryOptimizationNudge(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battery_nudge_title)) },
        text = { Text(stringResource(R.string.battery_nudge_message)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.battery_nudge_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.battery_nudge_not_now))
            }
        },
    )
}

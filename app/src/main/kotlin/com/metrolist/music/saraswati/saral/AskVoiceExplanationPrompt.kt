/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: security-ask-explanation — the one-time explanation shown before Saral's voice search is first
// routed through Ask Saraswati (SaralAsk), mirroring the shape of LocalAccessPrompt (one body of text, one
// button), not its copy.
package com.metrolist.music.saraswati.saral

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

@Composable
fun AskVoiceExplanationPrompt(onContinue: () -> Unit) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.ask_voice_explanation_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.ask_voice_explanation_body_hi),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = onContinue,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = stringResource(R.string.ask_voice_explanation_continue))
        }
    }
}

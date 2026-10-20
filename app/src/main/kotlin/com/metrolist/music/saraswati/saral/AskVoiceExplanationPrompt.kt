/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: security-ask-explanation — the one-time explanation shown before Saral's voice search is first
// routed through Ask Saraswati (SaralAsk), mirroring the shape of LocalAccessPrompt (one body of text, one
// button), not its copy.
//
// Fix (branch audit) — [AskVoiceExplanationCard]'s [textColor] is a required param, not MaterialTheme's
// default content color: SaralApp (SaralScreen.kt) is a bare MaterialTheme with no Surface, so the default
// LocalContentColor is black, unreadable on Saral's near-black background. Every other Saral Text passes its
// fixed TextPrimary explicitly for the same reason; those colors are private to SaralScreen.kt, so the caller
// passes its value in here.
//
// Fix (branch audit) — [AskVoiceExplanationScrim] is the whole scrim, not just the card: without a clickable
// of its own (the bug that shipped first), an imprecise tap could fall through to a tile, play/pause or the
// sign-in prompt underneath while the ask it was waiting on was still pending. It also doubles as the only way
// out besides the Okay button (SaralScreen's BackHandler swallows Back), the same way tapping ListeningOverlay's
// scrim cancels a search.
//
// [AskVoiceExplanationCard] takes its strings as plain parameters, with [AskVoiceExplanationPrompt] the only
// place that resolves them from resources: AskVoiceExplanationUiTest renders the card and the scrim directly
// (real color/clickable logic, the exact classes of bug that shipped) without needing Robolectric's unit tests
// to load real Android resources, which this module's test setup does not do (and enabling it broke ~28
// unrelated tests when tried — out of this change's scope to fix).
package com.metrolist.music.saraswati.saral

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

const val ASK_VOICE_EXPLANATION_BODY_TAG = "ask_voice_explanation_body"
const val ASK_VOICE_EXPLANATION_CONTINUE_TAG = "ask_voice_explanation_continue"
const val ASK_VOICE_EXPLANATION_SCRIM_TAG = "ask_voice_explanation_scrim"

/** The scrim, with the explanation card centred in it. The only composable SaralScreen calls. */
@Composable
fun AskVoiceExplanationOverlay(
    textColor: Color,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    closeLabel: String,
) {
    AskVoiceExplanationScrim(onDismiss = onDismiss, closeLabel = closeLabel) {
        AskVoiceExplanationPrompt(textColor = textColor, onContinue = onContinue)
    }
}

/** The scrim on its own: a full-screen background that consumes its own taps, with [content] centred in it. */
@Composable
internal fun AskVoiceExplanationScrim(
    onDismiss: () -> Unit,
    closeLabel: String,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xEB0A0806))
            .testTag(ASK_VOICE_EXPLANATION_SCRIM_TAG)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = closeLabel,
                role = Role.Button,
                onClick = onDismiss,
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun AskVoiceExplanationPrompt(
    textColor: Color,
    onContinue: () -> Unit,
) {
    AskVoiceExplanationCard(
        textColor = textColor,
        body = stringResource(R.string.ask_voice_explanation_body),
        bodyHi = stringResource(R.string.ask_voice_explanation_body_hi),
        continueLabel = stringResource(R.string.ask_voice_explanation_continue),
        onContinue = onContinue,
    )
}

/** The card's actual layout and behaviour, independent of where its strings came from. */
@Composable
internal fun AskVoiceExplanationCard(
    textColor: Color,
    body: String,
    bodyHi: String,
    continueLabel: String,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            modifier = Modifier.testTag(ASK_VOICE_EXPLANATION_BODY_TAG),
        )
        Text(
            text = bodyHi,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = onContinue,
            modifier = Modifier.padding(top = 16.dp).testTag(ASK_VOICE_EXPLANATION_CONTINUE_TAG),
        ) {
            Text(text = continueLabel)
        }
    }
}

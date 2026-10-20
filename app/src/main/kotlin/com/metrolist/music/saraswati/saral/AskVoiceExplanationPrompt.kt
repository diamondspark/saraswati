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
// sign-in prompt underneath while the ask it was waiting on was still pending.
//
// Fix (second-round audit, critical) — the card itself used to be a plain Column with no clickable, so it did
// not stop a tap from reaching the scrim behind it: reading the body text (or tapping anywhere but the Okay
// button) counted as tapping the scrim and silently dropped the pending ask, with no visible edge to the card
// and no labelled way to decline. The card is now a bordered Surface with a no-op clickable that absorbs its
// own taps, and declining is a real, visible "Not now" button wired to the same callback as the scrim
// ([onDecline] below and [AskVoiceExplanationScrim]'s [onDismiss] are the same callback in practice — see
// AskVoiceExplanationOverlay). This also fixes the TalkBack finding from the same audit: the explanation no
// longer reads as a single tap target where a double-tap anywhere drops the request.
//
// [AskVoiceExplanationCard] takes its strings as plain parameters, with [AskVoiceExplanationPrompt] the only
// place that resolves them from resources: AskVoiceExplanationUiTest renders the real card and scrim together
// (real color/clickable/placeholder-guard logic, the exact classes of bug that shipped) without needing
// Robolectric's unit tests to load real Android resources, which this module's test setup does not do (and
// enabling it broke ~28 unrelated tests when tried — out of this change's scope to fix).
package com.metrolist.music.saraswati.saral

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R

const val ASK_VOICE_EXPLANATION_BODY_TAG = "ask_voice_explanation_body"
const val ASK_VOICE_EXPLANATION_BODY_HI_TAG = "ask_voice_explanation_body_hi"
const val ASK_VOICE_EXPLANATION_CARD_TAG = "ask_voice_explanation_card"
const val ASK_VOICE_EXPLANATION_CONTINUE_TAG = "ask_voice_explanation_continue"
const val ASK_VOICE_EXPLANATION_DECLINE_TAG = "ask_voice_explanation_decline"
const val ASK_VOICE_EXPLANATION_SCRIM_TAG = "ask_voice_explanation_scrim"

// SARASWATI: second-round audit fix 3 — matches the ~19-26sp Noto Sans Devanagari convention every other
// Saral reading-text style uses (see SaralScreen.kt's ListeningStyle/StatusStyle etc.), instead of Material3's
// small defaults, so the owner's incoming Hindi line will read like the rest of the screen, not like a dialog
// from a different app. Defined locally (not imported) because SaralScreen's own font/colour vals are private
// to that file.
private val ExplanationFontFamily =
    FontFamily(
        Font(R.font.noto_sans_devanagari, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.noto_sans_devanagari, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )
private val ExplanationBodyStyle = TextStyle(fontFamily = ExplanationFontFamily, fontSize = 19.sp, lineHeight = 26.sp)
private val ExplanationBodyHiStyle =
    TextStyle(fontFamily = ExplanationFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp)

// Mirrors SaralScreen.kt's private Surface2/Border constants (same reason the colours above are duplicated,
// not imported): a visible edge is the point of this fix, so the card reads as its own tappable area, not
// empty space over the scrim.
private val CardSurfaceColor = Color(0xFF262019)
private val CardBorderColor = Color(0xFF3A2F20)

/**
 * True for the Hindi placeholder this branch ships with ("TODO: Hindi translation needed…", see
 * ask_voice_explanation_body_hi's comment in metrolist_strings.xml) and for anything shaped like it. A real
 * translation never starts with this word, so there is no way for the placeholder to reach a real phone's
 * screen even if the string resource itself isn't swapped out before release.
 */
internal fun isAskVoiceExplanationPlaceholder(text: String): Boolean = text.trimStart().startsWith("TODO", ignoreCase = true)

/** The scrim, with the explanation card centred in it. The only composable SaralScreen calls. */
@Composable
fun AskVoiceExplanationOverlay(
    textColor: Color,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    closeLabel: String,
) {
    AskVoiceExplanationScrim(onDismiss = onDismiss, closeLabel = closeLabel) {
        AskVoiceExplanationPrompt(textColor = textColor, onContinue = onContinue, onDecline = onDismiss)
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
    onDecline: () -> Unit,
) {
    AskVoiceExplanationCard(
        textColor = textColor,
        body = stringResource(R.string.ask_voice_explanation_body),
        bodyHi = stringResource(R.string.ask_voice_explanation_body_hi),
        continueLabel = stringResource(R.string.ask_voice_explanation_continue),
        declineLabel = stringResource(R.string.ask_voice_explanation_decline),
        onContinue = onContinue,
        onDecline = onDecline,
    )
}

/** The card's actual layout and behaviour, independent of where its strings came from. */
@Composable
internal fun AskVoiceExplanationCard(
    textColor: Color,
    body: String,
    bodyHi: String,
    continueLabel: String,
    declineLabel: String,
    onContinue: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .testTag(ASK_VOICE_EXPLANATION_CARD_TAG)
                .widthIn(max = 420.dp)
                // Fix (second-round audit, critical) — a no-op click: its only job is to consume the tap here
                // so it never reaches AskVoiceExplanationScrim's own clickable behind it. Reading the body text,
                // or tapping anywhere else on the card, must never count as declining.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        shape = RoundedCornerShape(16.dp),
        color = CardSurfaceColor,
        border = BorderStroke(1.dp, CardBorderColor),
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
        ) {
            Text(
                text = body,
                style = ExplanationBodyStyle,
                color = textColor,
                modifier = Modifier.testTag(ASK_VOICE_EXPLANATION_BODY_TAG),
            )
            // Fix (second-round audit 5, release safety net) — never render the Hindi placeholder this branch
            // ships with: a grandparent must never see "TODO: Hindi translation needed…" on screen, whatever
            // the string resource's current value is. The English line above still explains everything on its
            // own until the owner supplies the real copy.
            if (!isAskVoiceExplanationPlaceholder(bodyHi)) {
                Text(
                    text = bodyHi,
                    style = ExplanationBodyHiStyle,
                    color = textColor,
                    modifier = Modifier.padding(top = 12.dp).testTag(ASK_VOICE_EXPLANATION_BODY_HI_TAG),
                )
            }
            Row(
                modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.testTag(ASK_VOICE_EXPLANATION_DECLINE_TAG),
                ) {
                    Text(text = declineLabel)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onContinue,
                    modifier = Modifier.testTag(ASK_VOICE_EXPLANATION_CONTINUE_TAG),
                ) {
                    Text(text = continueLabel)
                }
            }
        }
    }
}

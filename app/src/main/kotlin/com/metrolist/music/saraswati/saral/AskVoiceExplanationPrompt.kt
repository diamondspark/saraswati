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
// Fix (second-round audit, critical) — the card itself used to be a plain Column with no tap-absorbing
// modifier at all, so it did not stop a tap from reaching the scrim behind it: reading the body text (or
// tapping anywhere but the Okay button) counted as tapping the scrim and silently dropped the pending ask,
// with no visible edge to the card and no labelled way to decline. The card is now a bordered Surface that
// absorbs its own taps (see the pointerInput/semantics modifiers below for how, and why that took two more
// rounds to get right), and declining is a real, visible "Not now" button wired to the same callback as the
// scrim ([onDecline] below and [AskVoiceExplanationScrim]'s [onDismiss] are the same callback in practice —
// see AskVoiceExplanationOverlay).
//
// [AskVoiceExplanationCard] takes its strings as plain parameters, with [AskVoiceExplanationPrompt] the only
// place that resolves them from resources: AskVoiceExplanationUiTest renders the real card and scrim together
// (real color/clickable/placeholder-guard logic, the exact classes of bug that shipped) without needing
// Robolectric's unit tests to load real Android resources, which this module's test setup does not do (and
// enabling it broke ~28 unrelated tests when tried — out of this change's scope to fix).
//
// Fix (third-round audit 2) — the Okay/Not now row used to be the last item inside the same scrolling Column
// as the body text. On a small phone with a large system font plus the incoming Hindi paragraph, that content
// can run well past the visible height, pushing the buttons out of view with nothing to say there is more
// below — the scrim (which declines) becomes the only tap target a person can actually see. The text is now
// the only part that scrolls (weight(1f, fill = false)); the button row sits in a fixed footer below it, so
// it is always on screen regardless of how long the text gets.
//
// Fix (third-round audit 3, accessibility) — the card's own tap-absorbing modifier is pointerInput +
// detectTapGestures, not clickable: clickable always adds a semantics OnClick action and merges descendants
// into one TalkBack node ("double-tap to activate"), even with indication = null and an empty lambda — which
// just moved the old "whole card is one dead tap target" bug instead of fixing it. pointerInput consumes the
// tap without adding any semantics of its own.
//
// Fix (fourth-round audit 1) — pointerInput also adds no *merge boundary*, so without a semantics() of its
// own the card's text was merging UPWARD into the scrim's clickable instead of being isolated: TalkBack read
// the whole explanation as the scrim's "Close" button, so a double-tap after hearing it out declined —
// against the person's intent, and worse than round 3's dead no-op. An empty
// Modifier.semantics(mergeDescendants = true) {} on the card gives its text its own node with no click action
// of its own, which stops the scrim's OnClick from reaching up through it, while Okay/Not now (each already
// its own merge boundary, from Button/TextButton) stay separate, reachable buttons.
//
// Fix (fourth-round audit 5) — Okay/Not now are stacked full-width, not side by side, so two 18sp buttons
// cannot get squeezed into wrapping mid-word at larger system font scales on a narrow phone.
package com.metrolist.music.saraswati.saral

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

// SARASWATI: third-round audit fix 4 — Okay/Not now rendered at Material3's default ~14sp, out of step with
// the rest of this screen's larger-text convention; matches ExplanationBodyStyle's family at a size that
// still reads as a button, not a paragraph.
private val ExplanationButtonStyle = TextStyle(fontFamily = ExplanationFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

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
            // Fix (fifth-round audit 3) — onClickLabel alone only supplies the "double-tap to ..." hint, not the
            // element's own name: without a contentDescription TalkBack announced an unnamed "Button". Set here
            // on the scrim specifically (not clearAndSetSemantics, which would also wipe the card's own text,
            // now correctly isolated from this node — see AskVoiceExplanationCard's semantics() fix).
            .semantics { contentDescription = closeLabel }
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
                // Fix (second-round audit, critical; third-round audit fix 3) — consumes the tap here so it
                // never reaches AskVoiceExplanationScrim's own clickable behind it: reading the body text, or
                // tapping anywhere else on the card, must never count as declining. pointerInput +
                // detectTapGestures on purpose, not clickable: clickable always adds a semantics click action,
                // which (see the semantics() line below for why that alone is not enough) would otherwise read
                // as one giant "double-tap to activate" TalkBack target over the whole card.
                .pointerInput(Unit) { detectTapGestures {} }
                // Fix (fourth-round audit 1) — pointerInput adds no merge boundary of its own, so without this
                // the card's text was merging UPWARD into AskVoiceExplanationScrim's clickable instead of being
                // isolated: TalkBack read the whole explanation as the scrim's "Close" button, so a double-tap
                // after hearing it out *declined* (worse than round 3's dead no-op, which at least did nothing).
                // An empty mergeDescendants=true boundary gives the card's own text its own node with no click
                // action, stopping it there, while Okay/Not now (their own merge boundaries, from Button/TextButton)
                // remain separate, reachable buttons; the scrim above shrinks to a bare "Close" button.
                .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(16.dp),
        color = CardSurfaceColor,
        border = BorderStroke(1.dp, CardBorderColor),
    ) {
        Column {
            // Fix (third-round audit 2) — only the text scrolls (weight + fill = false means it takes no more
            // than the space left over for it, but never forces the footer below off screen); the button
            // column below is a separate, fixed-position child of this outer Column, so Okay/Not now are
            // always visible.
            Column(
                modifier =
                    Modifier
                        .weight(weight = 1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
            ) {
                Text(
                    text = body,
                    style = ExplanationBodyStyle,
                    color = textColor,
                    modifier = Modifier.testTag(ASK_VOICE_EXPLANATION_BODY_TAG),
                )
                // Fix (second-round audit 5, release safety net) — never render the Hindi placeholder this
                // branch ships with: a grandparent must never see "TODO: Hindi translation needed…" on screen,
                // whatever the string resource's current value is. The English line above still explains
                // everything on its own until the owner supplies the real copy.
                if (!isAskVoiceExplanationPlaceholder(bodyHi)) {
                    Text(
                        text = bodyHi,
                        style = ExplanationBodyHiStyle,
                        color = textColor,
                        modifier = Modifier.padding(top = 12.dp).testTag(ASK_VOICE_EXPLANATION_BODY_HI_TAG),
                    )
                }
            }
            // Fix (fourth-round audit 5) — stacked full-width, not side by side: at larger system font scales
            // on a narrow (~320dp) phone, two 18sp buttons side by side could get tight enough to wrap mid-word.
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp).fillMaxWidth(),
            ) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().testTag(ASK_VOICE_EXPLANATION_CONTINUE_TAG),
                ) {
                    Text(text = continueLabel, style = ExplanationButtonStyle)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth().testTag(ASK_VOICE_EXPLANATION_DECLINE_TAG),
                ) {
                    Text(text = declineLabel, style = ExplanationButtonStyle)
                }
            }
        }
    }
}

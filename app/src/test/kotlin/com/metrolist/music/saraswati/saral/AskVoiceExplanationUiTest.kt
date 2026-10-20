/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: security-ask-explanation — the first review pass on AskVoiceExplanationPrompt/Overlay only ever
// ran a ViewModel, never the actual Composables, so a scrim that let taps fall through to the screen
// underneath was never caught. The second-round audit then actually ran the UI and found that reading the
// card itself (which had no clickable of its own) counted as tapping the scrim and silently dropped the
// pending ask — this file's first version "rendered the scrim" but stood a plain Button in for the card,
// so it could not have caught that either. These tests now render the real AskVoiceExplanationCard, inside
// the real AskVoiceExplanationScrim, and drive both with real taps.
//
// A pixel-level check for a third finding (an unset/black Text color on Saral's near-black background) was
// attempted here too — drawing the content view's own Canvas directly, since compose-ui-test's
// captureToImage() goes through PixelCopy.request, which Robolectric does not implement at all. Even so, and
// with @GraphicsMode(NATIVE), the brightest pixel sampled anywhere in the rendered view never rose above the
// flat background color: this Robolectric setup does not actually draw text glyphs onto a Canvas, for either
// color, so no assertion built on it would ever prove anything either way. That check is not included below.
// The fix itself (textColor is a required, non-defaulted parameter, and SaralScreen is the only caller) is a
// compile-time guarantee instead: a call site that forgets to pass a color fails the build, not just a
// screenshot.
package com.metrolist.music.saraswati.saral

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

// SARASWATI: security-ask-explanation — createComposeRule() launches its ComponentActivity through an
// explicit Intent that Robolectric resolves against "org.robolectric.default" rather than this module's real
// merged manifest/package ("Unable to resolve activity"), for reasons independent of @Config.
// createEmptyComposeRule() sidesteps that: it provides the same node-query/assertion API without launching
// anything itself, and Robolectric.setupActivity() below builds the host Activity directly (lifecycle calls,
// not PackageManager intent resolution), onto which setContent() is then called exactly as MainActivity does.
@RunWith(RobolectricTestRunner::class)
class AskVoiceExplanationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        Robolectric.setupActivity(ComponentActivity::class.java).setContent(content = content)
    }

    private class Callbacks {
        var continued = false
        var declined = false
    }

    /** The real production tree: the real scrim around the real card, with plain (non-resource) strings. */
    private fun renderRealExplanation(): Callbacks {
        val callbacks = Callbacks()
        setContent {
            AskVoiceExplanationScrim(onDismiss = { callbacks.declined = true }, closeLabel = "Close") {
                AskVoiceExplanationCard(
                    textColor = Color(0xFFF3ECDF),
                    body = "Each time you speak a request, Saraswati sends the words to a computer helper.",
                    bodyHi = "body hi",
                    continueLabel = "Okay",
                    declineLabel = "Not now",
                    onContinue = { callbacks.continued = true },
                    onDecline = { callbacks.declined = true },
                )
            }
        }
        return callbacks
    }

    // SARASWATI: second-round audit fix 1 & 2 — the exact bug the auditor confirmed live: tapping the body
    // text used to count as tapping the scrim and silently dropped the pending ask. The card now has its own
    // no-op clickable to absorb this tap before it reaches the scrim.
    @Test
    fun `tapping the body text does not dismiss the explanation`() {
        val callbacks = renderRealExplanation()

        // A coordinate-based tap, not performClick()'s semantics-action shortcut: the Text node itself has no
        // click action, so this is the only way to prove the tap is caught by the card behind it, not ignored
        // because there was nothing to click in the first place. useUnmergedNode: the card's own clickable
        // merges this Text's semantics into its own node, so the default (merged) tree cannot address it alone.
        compose.onNodeWithTag(ASK_VOICE_EXPLANATION_BODY_TAG, useUnmergedTree = true).performTouchInput { click() }

        assertFalse("reading the body text must not decline for the person", callbacks.declined)
        assertFalse(callbacks.continued)
    }

    // SARASWATI: second-round audit fix 1 — tapping anywhere else on the card (not a button) must also not
    // decline: the whole card, not just the text node, absorbs its own taps.
    @Test
    fun `tapping elsewhere on the card does not dismiss the explanation`() {
        val callbacks = renderRealExplanation()

        // SARASWATI: third-round audit fix 3 — pointerInput (unlike the clickable it replaced) adds no merge
        // boundary of its own, so the card's tag is only addressable in the unmerged tree now.
        compose.onNodeWithTag(ASK_VOICE_EXPLANATION_CARD_TAG, useUnmergedTree = true).performTouchInput { click(Offset(4f, 4f)) }

        assertFalse(callbacks.declined)
        assertFalse(callbacks.continued)
    }

    @Test
    fun `tapping the scrim outside the card calls onDismiss instead of falling through to what's behind it`() {
        var underlyingTapped = false
        val callbacks = Callbacks()
        setContent {
            Box(Modifier.size(400.dp, 400.dp)) {
                // Stands in for a tile, play/pause or the sign-in prompt behind the overlay.
                Button(onClick = { underlyingTapped = true }, modifier = Modifier.fillMaxSize()) {}
                AskVoiceExplanationScrim(onDismiss = { callbacks.declined = true }, closeLabel = "Close") {
                    AskVoiceExplanationCard(
                        textColor = Color(0xFFF3ECDF),
                        body = "body",
                        bodyHi = "body hi",
                        continueLabel = "Okay",
                        declineLabel = "Not now",
                        onContinue = { callbacks.continued = true },
                        onDecline = { callbacks.declined = true },
                    )
                }
            }
        }

        // Near a corner of the scrim, well outside the centred card.
        compose.onNodeWithTag(ASK_VOICE_EXPLANATION_SCRIM_TAG).performTouchInput { click(Offset(10f, 10f)) }

        assertTrue("the scrim's own onDismiss must fire", callbacks.declined)
        assertFalse(callbacks.continued)
        assertFalse("a tap on the scrim must not reach whatever is behind it", underlyingTapped)
    }

    @Test
    fun `tapping Okay runs onContinue, not onDecline`() {
        val callbacks = renderRealExplanation()

        compose.onNodeWithTag(ASK_VOICE_EXPLANATION_CONTINUE_TAG).performClick()

        assertTrue(callbacks.continued)
        assertFalse("Okay must not also decline", callbacks.declined)
    }

    // SARASWATI: second-round audit fix 1 — a real, labelled way to decline, instead of it being an
    // accidental side effect of reading.
    @Test
    fun `tapping Not now runs onDecline, not onContinue`() {
        val callbacks = renderRealExplanation()

        compose.onNodeWithTag(ASK_VOICE_EXPLANATION_DECLINE_TAG).performClick()

        assertTrue(callbacks.declined)
        assertFalse(callbacks.continued)
    }

    // SARASWATI: security-ask-explanation (fix 5, release safety net) — the auditor confirmed the Hindi
    // placeholder ("TODO: Hindi translation needed…") literally renders on screen. isAskVoiceExplanationPlaceholder
    // is the guard; these are its two sides.
    @Test
    fun `isAskVoiceExplanationPlaceholder recognises the TODO placeholder this branch ships with`() {
        assertTrue(isAskVoiceExplanationPlaceholder("TODO: Hindi translation needed for ask_voice_explanation_body"))
        assertTrue(isAskVoiceExplanationPlaceholder("  todo: something"))
    }

    @Test
    fun `isAskVoiceExplanationPlaceholder leaves real copy alone`() {
        assertFalse(isAskVoiceExplanationPlaceholder("सरस्वती आपकी बात सुनकर एक सहायक को भेजती है।"))
        assertFalse(isAskVoiceExplanationPlaceholder(""))
    }

    @Test
    fun `the placeholder Hindi line is never rendered, the real body still is`() {
        setContent {
            AskVoiceExplanationCard(
                textColor = Color(0xFFF3ECDF),
                body = "body",
                bodyHi = "TODO: Hindi translation needed for ask_voice_explanation_body",
                continueLabel = "Okay",
                declineLabel = "Not now",
                onContinue = {},
                onDecline = {},
            )
        }

        assertTrue(compose.onAllNodesWithTag(ASK_VOICE_EXPLANATION_BODY_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            "the placeholder must never be rendered",
            compose.onAllNodesWithTag(ASK_VOICE_EXPLANATION_BODY_HI_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `a real Hindi line is rendered alongside the body`() {
        setContent {
            AskVoiceExplanationCard(
                textColor = Color(0xFFF3ECDF),
                body = "body",
                bodyHi = "सरस्वती आपकी बात सुनकर एक सहायक को भेजती है।",
                continueLabel = "Okay",
                declineLabel = "Not now",
                onContinue = {},
                onDecline = {},
            )
        }

        assertTrue(compose.onAllNodesWithTag(ASK_VOICE_EXPLANATION_BODY_HI_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}

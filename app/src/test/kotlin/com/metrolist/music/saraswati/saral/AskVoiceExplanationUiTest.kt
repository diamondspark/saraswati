/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: security-ask-explanation (branch audit) — the first two review passes on
// AskVoiceExplanationPrompt/Overlay only ever ran a ViewModel, never the actual Composables, so a scrim that
// let taps fall through to the screen underneath was never caught. This renders the real production
// AskVoiceExplanationScrim (AskVoiceExplanationPrompt.kt) under Robolectric and drives it with real taps.
//
// A pixel-level check for the OTHER branch-audit finding (an unset/black Text color on Saral's near-black
// background) was attempted here too — drawing the content view's own Canvas directly, since compose-ui-test's
// captureToImage() goes through PixelCopy.request, which Robolectric does not implement at all. Even so, and
// with @GraphicsMode(NATIVE), the brightest pixel sampled anywhere in the rendered view never rose above the
// flat background color: this Robolectric setup does not actually draw text glyphs onto a Canvas, for either
// color, so no assertion built on it would ever prove anything either way. That check is not included below.
// The fix itself (textColor is a required, non-defaulted parameter on AskVoiceExplanationCard/Prompt, and
// SaralScreen is their only caller) is a compile-time guarantee instead: a call site that forgets to pass a
// color fails the build, not just a screenshot.
package com.metrolist.music.saraswati.saral

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

// SARASWATI: security-ask-explanation (branch audit) — createComposeRule() launches its ComponentActivity
// through an explicit Intent that Robolectric resolves against "org.robolectric.default" rather than this
// module's real merged manifest/package ("Unable to resolve activity"), for reasons independent of @Config.
// createEmptyComposeRule() sidesteps that: it provides the same node-query/assertion API without launching
// anything itself, and Robolectric.setupActivity() below builds the host Activity directly (lifecycle calls,
// not PackageManager intent resolution), onto which setContent() is then called exactly as MainActivity does.
@RunWith(RobolectricTestRunner::class)
class AskVoiceExplanationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        Robolectric.setupActivity(ComponentActivity::class.java).setContent(content = content)
    }

    // SARASWATI: security-ask-explanation (branch audit fix 2 & 3) — AskVoiceExplanationScrim is the real
    // production scrim (same file, used by AskVoiceExplanationOverlay); only its content is a plain Button
    // here instead of the real card, so this test does not depend on real Android resources either.
    @Test
    fun `tapping the scrim calls onDismiss instead of falling through to what's behind it`() {
        var dismissed = false
        var continueTapped = false
        var underlyingTapped = false
        setContent {
            Box(Modifier.size(400.dp, 400.dp)) {
                // Stands in for a tile, play/pause or the sign-in prompt behind the overlay.
                Button(onClick = { underlyingTapped = true }, modifier = Modifier.fillMaxSize()) {}
                AskVoiceExplanationScrim(onDismiss = { dismissed = true }, closeLabel = "Close") {
                    Button(onClick = { continueTapped = true }, modifier = Modifier.testTag(ASK_VOICE_EXPLANATION_CONTINUE_TAG)) {
                        Text("Okay")
                    }
                }
            }
        }

        // Near a corner of the scrim, away from the centred button, so this cannot land on Okay.
        compose.onNodeWithTag(ASK_VOICE_EXPLANATION_SCRIM_TAG).performTouchInput { click(Offset(10f, 10f)) }

        assertTrue("the scrim's own onDismiss must fire", dismissed)
        assertFalse(continueTapped)
        assertFalse("a tap on the scrim must not reach whatever is behind it", underlyingTapped)
    }

    // SARASWATI: security-ask-explanation (branch audit fix 2 & 3) — the card itself must still consume its own
    // tap first: Okay must not also dismiss.
    @Test
    fun `tapping Okay runs its own onClick, not the scrim's onDismiss`() {
        var dismissed = false
        var continueTapped = false
        setContent {
            AskVoiceExplanationScrim(onDismiss = { dismissed = true }, closeLabel = "Close") {
                Button(onClick = { continueTapped = true }, modifier = Modifier.testTag(ASK_VOICE_EXPLANATION_CONTINUE_TAG)) {
                    Text("Okay")
                }
            }
        }

        compose.onNodeWithTag(ASK_VOICE_EXPLANATION_CONTINUE_TAG).performClick()

        assertTrue(continueTapped)
        assertFalse("Okay must not also trigger the scrim's dismiss", dismissed)
    }
}

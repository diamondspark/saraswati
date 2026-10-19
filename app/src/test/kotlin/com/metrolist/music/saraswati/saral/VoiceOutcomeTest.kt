/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — the system speech recogniser ends in more
// ways than "here is what you said". Each one has to reach the screen as its
// own message; only a deliberate cancel closes quietly.
package com.metrolist.music.saraswati.saral

import android.app.Activity
import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceOutcomeTest {
    private val guesses = arrayListOf("लग जा गले", "lag ja gale")

    @Test
    fun `an OK result carries the guesses`() {
        assertEquals(VoiceOutcome.Heard(guesses), VoiceOutcome.fromActivityResult(Activity.RESULT_OK, guesses))
    }

    @Test
    fun `an OK result with no list is still heard, and left for the view model to call not understood`() {
        assertEquals(VoiceOutcome.Heard(null), VoiceOutcome.fromActivityResult(Activity.RESULT_OK, null))
    }

    @Test
    fun `backing out is a cancel`() {
        assertEquals(VoiceOutcome.Cancelled, VoiceOutcome.fromActivityResult(Activity.RESULT_CANCELED, null))
    }

    @Test
    fun `no match means it was not understood`() {
        assertEquals(VoiceOutcome.NotHeard, VoiceOutcome.fromActivityResult(RecognizerIntent.RESULT_NO_MATCH, null))
    }

    @Test
    fun `network and server errors mean offline`() {
        assertEquals(VoiceOutcome.Offline, VoiceOutcome.fromActivityResult(RecognizerIntent.RESULT_NETWORK_ERROR, null))
        assertEquals(VoiceOutcome.Offline, VoiceOutcome.fromActivityResult(RecognizerIntent.RESULT_SERVER_ERROR, null))
    }

    @Test
    fun `audio, client and unknown errors mean it did not work`() {
        assertEquals(VoiceOutcome.Failed, VoiceOutcome.fromActivityResult(RecognizerIntent.RESULT_AUDIO_ERROR, null))
        assertEquals(VoiceOutcome.Failed, VoiceOutcome.fromActivityResult(RecognizerIntent.RESULT_CLIENT_ERROR, null))
        assertEquals(VoiceOutcome.Failed, VoiceOutcome.fromActivityResult(12345, null))
    }

    @Test
    fun `an error result ignores any guesses that came with it`() {
        assertEquals(VoiceOutcome.NotHeard, VoiceOutcome.fromActivityResult(RecognizerIntent.RESULT_NO_MATCH, guesses))
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 fix round 1 — every way the system speech recogniser can
// end, as the screen needs to tell them apart. Only [Cancelled] is quiet.
package com.metrolist.music.saraswati.saral

import android.app.Activity
import android.speech.RecognizerIntent

sealed interface VoiceOutcome {
    /** The recogniser returned guesses, best first. A null or empty list is "not understood". */
    data class Heard(
        val guesses: List<String>?,
    ) : VoiceOutcome

    /** The person backed out. */
    data object Cancelled : VoiceOutcome

    /** The recogniser could not make out any words. */
    data object NotHeard : VoiceOutcome

    /** The recogniser needed the network and had none (or the server did not answer). */
    data object Offline : VoiceOutcome

    /** Microphone, client or unknown trouble. */
    data object Failed : VoiceOutcome

    companion object {
        fun fromActivityResult(
            resultCode: Int,
            guesses: List<String>?,
        ): VoiceOutcome =
            when (resultCode) {
                Activity.RESULT_OK -> Heard(guesses)
                Activity.RESULT_CANCELED -> Cancelled
                RecognizerIntent.RESULT_NO_MATCH -> NotHeard
                RecognizerIntent.RESULT_NETWORK_ERROR, RecognizerIntent.RESULT_SERVER_ERROR -> Offline
                else -> Failed
            }
    }
}

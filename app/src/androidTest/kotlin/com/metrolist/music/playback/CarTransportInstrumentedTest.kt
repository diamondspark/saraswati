package com.metrolist.music.playback

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// SARASWATI: car transport after a voice queue. The queue is built once by one voice query and never played. Pause,
// next, previous and seek are sent as player commands, the way the car sends them. Rows go to logcat under CARTRANSPORT.
// Then the controller is released and reconnected to record whether a queue comes back. An in-process reconnect does not
// invoke onPlaybackResumption (the system calls it for a media-button resume), so that is recorded, not asserted.

private const val TRANSPORT_TAG = "CARTRANSPORT"
private const val TRANSPORT_QUERY = "tum hi ho"
private const val INDEX_SETTLE_MS = 1_000L

private data class TransportState(
    val index: Int,
    val count: Int,
    val playbackState: Int,
    val isPlaying: Boolean,
    val connected: Boolean,
    val positionMs: Long,
)

private class TransportStep(
    val name: String,
    val command: (MediaController) -> Unit,
    val expectedIndex: (before: Int) -> Int,
)

private val STEPS = listOf(
    TransportStep("pause", { it.pause() }, { before -> before }),
    TransportStep("next", { it.seekToNextMediaItem() }, { before -> before + 1 }),
    TransportStep("previous", { it.seekToPreviousMediaItem() }, { before -> before - 1 }),
    TransportStep("seekTo(0)", { it.seekTo(0) }, { before -> before }),
)

@RunWith(AndroidJUnit4::class)
class CarTransportInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val session = AaVoiceSession(instrumentation, TRANSPORT_TAG)

    @Test
    fun carTransport() {
        session.connect()
        val failures = mutableListOf<String>()
        try {
            session.resetQueue()
            session.submitVoice(TRANSPORT_QUERY)
            val queued = session.awaitQueue()
            val start = state()
            log("queued", "-", start, "count=${queued.count}\tfirst=${queued.first}\tfirstArtist=${queued.firstArtist}")
            notPlayingAt("queued", start, failures)
            if (queued.count < 2) {
                failures += "precondition: queue has ${queued.count} track(s); transport needs at least 2"
            } else {
                STEPS.forEach { step -> runStep(step, failures) }
            }
        } finally {
            session.release()
        }
        checkResumption(failures)
        Log.i(TRANSPORT_TAG, "SUMMARY\tfailed=${failures.size}")
        assertTrue(
            "${failures.size} transport checks failed: ${failures.joinToString("; ")}",
            failures.isEmpty(),
        )
    }

    private fun runStep(step: TransportStep, failures: MutableList<String>) {
        idle()
        val before = state()
        notPlayingAt("before ${step.name}", before, failures)
        val expected = step.expectedIndex(before.index)
        val threw = runCatching { session.onMain { step.command(it) } }.exceptionOrNull()
        Thread.sleep(INDEX_SETTLE_MS)
        val after = awaitIndex(expected)
        notPlayingAt("after ${step.name}", after, failures)
        val verdict = when {
            threw != null -> "threw ${threw.javaClass.name}: ${threw.message}"
            !after.connected -> "controller disconnected"
            after.index != expected -> "index ${after.index}, expected $expected"
            else -> null
        }
        log(step.name, verdict ?: "ok", after, "before=${before.index}\texpected=$expected")
        verdict?.let { failures += "${step.name}: $it" }
    }

    // play() is never called, so nothing may be playing at any checkpoint. A true here means something auto-played.
    private fun notPlayingAt(where: String, s: TransportState, failures: MutableList<String>) {
        if (s.isPlaying) failures += "$where: isPlaying is true, nothing should be playing"
    }

    // Commands are applied by the session asynchronously, so poll for the expected index before reading the state.
    private fun awaitIndex(expected: Int): TransportState {
        val deadline = System.currentTimeMillis() + AA_SETTLE_TIMEOUT_MS
        var current = state()
        while (current.index != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(AA_POLL_MS)
            current = state()
        }
        return current
    }

    private fun checkResumption(failures: MutableList<String>) {
        idle()
        val reconnect = AaVoiceSession(instrumentation, TRANSPORT_TAG)
        try {
            reconnect.connect()
            Thread.sleep(INDEX_SETTLE_MS)
            val snap = reconnect.snapshot()
            notPlayingAt("reconnect", reconnect.onMain { TransportState(0, 0, 0, it.isPlaying, it.isConnected, 0) }, failures)
            Log.i(
                TRANSPORT_TAG,
                "RESUME\tqueueAfterReconnect=${snap.count}\tfirst=${snap.first}\tresumable=${snap.count > 0}\t" +
                    "note=onPlaybackResumption not invoked by in-process reconnect",
            )
        } catch (e: Throwable) {
            failures += "reconnect threw ${e.javaClass.name}: ${e.message}"
        } finally {
            reconnect.release()
        }
    }

    private fun state(): TransportState = session.onMain { c ->
        TransportState(
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            playbackState = c.playbackState,
            isPlaying = c.isPlaying,
            connected = c.isConnected,
            positionMs = c.currentPosition,
        )
    }

    private fun log(name: String, verdict: String, s: TransportState, detail: String) {
        Log.i(
            TRANSPORT_TAG,
            "STEP\t$name\tindex=${s.index}\tcount=${s.count}\tstate=${stateName(s.playbackState)}\t" +
                "isPlaying=${s.isPlaying}\tconnected=${s.connected}\tpositionMs=${s.positionMs}\t$detail\t$verdict",
        )
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }
}

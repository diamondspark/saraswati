/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 12 — the player side of the tap-to-audio timing. A tap is a playlist change or a seek, never
// an automatic transition from one song to the next; the end is the first audio after it. Each finished tap is an
// async trace section, SarTapToAudioOffline or SarTapToAudioNetwork, that the benchmarks read. Release builds write
// no log line: the Timber call is behind BuildConfig.DEBUG.
package com.metrolist.music.saraswati.perf

import android.os.Trace
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.metrolist.music.BuildConfig
import timber.log.Timber

class TapToAudioTracer(
    private val clock: TapToAudioClock,
    /** Synchronous: true when the song is local, or its first chunk is in the download or player cache. */
    private val isOfflineNow: (String) -> Boolean,
) : AnalyticsListener {
    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if (mediaItem == null) return
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
        ) {
            return
        }
        val offline = isOfflineNow(mediaItem.mediaId)
        clock.onTap(mediaItem.mediaId, offline)
        Trace.beginAsyncSection(sectionName(offline), 0)
    }

    override fun onPlayWhenReadyChanged(
        eventTime: AnalyticsListener.EventTime,
        playWhenReady: Boolean,
        reason: Int,
    ) {
        if (!playWhenReady) clock.onPause()
    }

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        positionUs: Long,
    ) {
        val sample = clock.onAudioAdvancing() ?: return
        Trace.endAsyncSection(sectionName(sample.offline), 0)
        if (BuildConfig.DEBUG) {
            Timber.d("tapToAudio offline=${sample.offline} ms=${sample.elapsedMs}")
        }
    }

    private fun sectionName(offline: Boolean): String = if (offline) OFFLINE_SECTION else NETWORK_SECTION

    private companion object {
        const val OFFLINE_SECTION = "SarTapToAudioOffline"
        const val NETWORK_SECTION = "SarTapToAudioNetwork"
    }
}

/**
 * Wraps a car browse so its work shows as one trace section. Inline so that suspending work inside [block] is allowed.
 * The section begins and ends on whichever threads the coroutine runs on, so it is a rough span, not a per-thread one.
 */
inline fun <T> sarTraceSection(
    name: String,
    block: () -> T,
): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

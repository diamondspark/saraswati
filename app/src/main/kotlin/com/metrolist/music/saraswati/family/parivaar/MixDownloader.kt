/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 12 — puts the Parivaar Mix on the phone for the car and for dead zones, but only on an
// unmetered network (Wi-Fi), and only the songs that are not downloaded or already on their way.
package com.metrolist.music.saraswati.family.parivaar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import androidx.media3.exoplayer.offline.Download
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.DownloadUtil

class MixDownloader(
    private val isUnmetered: () -> Boolean,
    private val isDownloadedOrQueued: (String) -> Boolean,
    private val enqueue: (MixTrack) -> Unit,
) {
    fun downloadMissing(tracks: List<MixTrack>) {
        if (!isUnmetered()) return
        tracks.filterNot { isDownloadedOrQueued(it.videoId) }.forEach(enqueue)
    }

    companion object {
        fun create(
            context: Context,
            downloadUtil: DownloadUtil,
        ): MixDownloader {
            val connectivity = context.getSystemService<ConnectivityManager>()
            return MixDownloader(
                isUnmetered = {
                    val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
                },
                isDownloadedOrQueued = { id ->
                    when (downloadUtil.downloads.value[id]?.state) {
                        Download.STATE_COMPLETED, Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> true
                        else -> false
                    }
                },
                enqueue = { downloadUtil.download(it.toMediaMetadata()) },
            )
        }
    }
}

internal fun MixTrack.toMediaMetadata(): MediaMetadata =
    MediaMetadata(
        id = videoId,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = null, name = artist)),
        duration = durationMs?.let { (it / 1000).toInt() } ?: -1,
        thumbnailUrl = thumbnailUrl,
    )

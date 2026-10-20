/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 7 — the car's Prahar row, first on Home. Shown only for the mix of the current window and
// day, so a stale window never appears. Built from cached data only; never waits.
package com.metrolist.music.saraswati.craft.prahar

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.music.R
import com.metrolist.music.saraswati.car.CarResources
import java.time.LocalDateTime

internal object CarPraharRow {
    const val MEDIA_ID = "prahar_mix"

    /** True when [mix] is the one for the window and day [now] falls in. */
    fun isCurrent(
        mix: PraharMix?,
        now: LocalDateTime,
    ): Boolean =
        mix != null &&
            mix.tracks.isNotEmpty() &&
            mix.prahar == Prahar.of(now.toLocalTime()) &&
            mix.day == now.toLocalDate()

    fun item(
        mix: PraharMix?,
        now: LocalDateTime,
        resources: CarResources,
    ): MediaItem? {
        if (mix == null || !isCurrent(mix, now)) return null
        val title = String.format(resources.string(R.string.car_prahar_title), mix.prahar.hindi)
        val subtitle = String.format(resources.string(R.string.car_prahar_subtitle), mix.prahar.english)
        return MediaItem
            .Builder()
            .setMediaId(MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(resources.iconUri(R.drawable.queue_music))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build(),
            ).build()
    }
}

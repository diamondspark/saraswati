/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 11 — the family's stations as the car shows them, and the
// ids that make choosing one play a radio.
//
// A station row's id carries the station's seed video: "car_stations/<position>/<seed>".
// Choosing the row hands that id back to us (onSetMediaItems), and the seed is
// what the radio is built from, the same way a Saral tile builds it. The id
// comes back from outside the app, so it is believed only if it has exactly
// this shape and the seed is a plain 11-character YouTube video id; anything
// else plays nothing. The position keeps two stations that share a video apart.
//
// A station whose seed is not such an id is not offered at all: the car must
// never list a row that cannot play. The owner's editor only saves valid ids,
// so this only ever drops a row the hub was given some other way.
//
// Names are Saral's: the four starting stations show their approved Hindi names
// until the owner renames them.
package com.metrolist.music.saraswati.car

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.music.playback.MusicService
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationIcons
import com.metrolist.music.saraswati.family.StationVideo
import com.metrolist.music.saraswati.saral.SaralLabels

internal object CarStations {
    private val STATION_ID = Regex("^${Regex.escape(MusicService.CAR_STATIONS)}/(\\d{1,3})/(${StationVideo.ID_PATTERN})$")

    fun mediaId(
        position: Int,
        seedVideoId: String,
    ): String = "${MusicService.CAR_STATIONS}/$position/$seedVideoId"

    /** The seed video a row made by [mediaId] carries, or null for any id that is not exactly such a row. */
    fun seedFrom(mediaId: String): String? = STATION_ID.matchEntire(mediaId)?.groupValues?.get(2)

    /** The stations the car can play, in the owner's order. */
    fun playable(stations: List<Station>): List<Station> = stations.filter { StationVideo.isVideoId(it.seedVideoId) }

    fun items(
        resources: CarResources,
        stations: List<Station>,
    ): List<MediaItem> =
        playable(stations).mapIndexed { position, station ->
            MediaItem
                .Builder()
                .setMediaId(mediaId(position, station.seedVideoId))
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(titleOf(resources, station))
                        .setArtworkUri(resources.iconUri(StationIcons.drawableFor(station.iconIndex)))
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                        .build(),
                ).build()
        }

    private fun titleOf(
        resources: CarResources,
        station: Station,
    ): String = SaralLabels.hindiLabelFor(station)?.let { resources.string(it) } ?: station.title
}

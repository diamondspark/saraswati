/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 15 — the car's Family folder: the Parivaar Mix, the family's shared playlists, and the
// Sangat start/end row, in that order. Every row is playable (choosing one plays it, or toggles Sangat), and a
// feature the owner has switched off leaves no row. A plain function over what the phone already holds, so the
// car never waits for the hub or YouTube to see this folder.
//
// The Parivaar Mix and Sangat rows carry fixed ids that onSetMediaItems recognises exactly. A playlist row uses the
// same YouTube-playlist id form the Home rows use (youtube_playlist/<id>); the id is shown only if it is a plain
// YouTube playlist id, so a malformed one from the hub can never become a path of its own.
package com.metrolist.music.saraswati.car

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.music.R
import com.metrolist.music.playback.MusicService
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyPlaylist
import com.metrolist.music.saraswati.family.parivaar.ParivaarMix
import com.metrolist.music.saraswati.family.sangat.CarSangatState

internal object CarFamilyTree {
    const val PARIVAAR_MIX_ID = "parivaar_mix"
    const val SANGAT_TOGGLE_ID = "sangat_toggle"

    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{2,64}$")

    fun playlistMediaId(playlistId: String): String = "${MusicService.YOUTUBE_PLAYLIST}/$playlistId"

    fun children(
        mix: ParivaarMix,
        playlists: List<FamilyPlaylist>,
        enabled: (FamilyFeature) -> Boolean,
        sangat: CarSangatState,
        resources: CarResources,
    ): List<MediaItem> =
        buildList {
            if (enabled(FamilyFeature.PARIVAAR_MIX) && mix.tracks.isNotEmpty()) {
                add(
                    playable(
                        PARIVAAR_MIX_ID,
                        resources.string(R.string.car_family_parivaar_mix),
                        resources.iconUri(R.drawable.library_music_outlined),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                )
            }
            if (enabled(FamilyFeature.FAMILY_PLAYLISTS)) {
                playlists.filter { PLAYLIST_ID.matches(it.playlistId) }.forEach {
                    add(
                        playable(
                            playlistMediaId(it.playlistId),
                            it.title,
                            resources.iconUri(R.drawable.queue_music),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        ),
                    )
                }
            }
            if (enabled(FamilyFeature.SANGAT)) {
                val title =
                    if (sangat.hosting) {
                        String.format(resources.string(R.string.car_family_sangat_end), sangat.joinedCount)
                    } else {
                        resources.string(R.string.car_family_sangat_start)
                    }
                add(playable(SANGAT_TOGGLE_ID, title, resources.iconUri(R.drawable.group_outlined), MediaMetadata.MEDIA_TYPE_MUSIC))
            }
        }

    private fun playable(
        id: String,
        title: String,
        icon: android.net.Uri,
        mediaType: Int,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setArtworkUri(icon)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(mediaType)
                    .build(),
            ).build()
}

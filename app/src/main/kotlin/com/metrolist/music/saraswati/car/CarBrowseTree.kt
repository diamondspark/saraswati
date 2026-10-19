/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 11 — the top of the car's browse tree: four tabs, in this
// order: Home, Library, Stations, Family.
//
//  - Home is what the "Mixes" folder (YOUTUBE_PLAYLIST) used to hold, now always
//    offered instead of behind a setting.
//  - Library is what the root used to be, unchanged: the person's own sections
//    (Liked, Songs, Artists, Albums, Playlists), in their order.
//  - Stations are the family's stations from the phone's own cache, read at
//    once. Nothing here asks the hub, or waits: the car is often offline, and a
//    folder that blocks would leave it blank (Review Focus 5). The cache fills
//    from storage a moment after the process starts and the car can ask first,
//    so an empty answer is possible; carStationsChanges tells the car to ask
//    again when the cache has loaded or changed.
//  - Family is one greyed row, "Sangat (coming soon)": the family features are
//    Phase 4, and the car never advertises what does not exist yet.
//
// The shelves for Home and the sections for Library are handed in, because they
// come from YouTube and the settings store, which this class must stay free of.
package com.metrolist.music.saraswati.car

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.music.R
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.playback.MusicService
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

internal class CarBrowseTree(
    private val resources: CarResources,
    private val stations: StationsCache,
    private val librarySections: suspend () -> List<Pair<AndroidAutoSection, Boolean>>,
    private val homeShelves: suspend () -> List<MediaItem>,
) {
    /** The children of [parentId] if it is the root or one of the four tabs, else null: the rest of the browse tree answers. */
    suspend fun childrenOf(parentId: String): List<MediaItem>? =
        when (parentId) {
            MusicService.ROOT -> tabs()
            MusicService.CAR_LIBRARY -> libraryItems()
            // The old "Mixes" id still answers, as Home, for a car that remembers it.
            MusicService.CAR_HOME, MusicService.YOUTUBE_PLAYLIST -> homeShelves()
            MusicService.CAR_STATIONS -> stationItems()
            MusicService.CAR_FAMILY -> familyItems()
            else -> null
        }

    private fun tabs(): List<MediaItem> =
        listOf(
            tab(MusicService.CAR_HOME, resources.string(R.string.home), R.drawable.home_outlined),
            tab(MusicService.CAR_LIBRARY, resources.string(R.string.filter_library), R.drawable.library_music_outlined),
            tab(MusicService.CAR_STATIONS, resources.string(R.string.car_tab_stations), R.drawable.radio),
            tab(MusicService.CAR_FAMILY, resources.string(R.string.family), R.drawable.group_outlined),
        )

    private fun tab(
        id: String,
        title: String,
        icon: Int,
    ) = carBrowsableMediaItem(id, title, null, resources.iconUri(icon), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    private suspend fun libraryItems(): List<MediaItem> =
        librarySections()
            .filter { (_, enabled) -> enabled }
            .ifEmpty { listOf(AndroidAutoSection.LIKED to true) }
            .map { (section, _) ->
                when (section) {
                    AndroidAutoSection.LIKED ->
                        carBrowsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                            resources.string(R.string.liked_songs),
                            null,
                            resources.iconUri(R.drawable.favorite),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        )
                    AndroidAutoSection.SONGS ->
                        carBrowsableMediaItem(
                            MusicService.SONG,
                            resources.string(R.string.songs),
                            null,
                            resources.iconUri(R.drawable.music_note),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        )
                    AndroidAutoSection.ARTISTS ->
                        carBrowsableMediaItem(
                            MusicService.ARTIST,
                            resources.string(R.string.artists),
                            null,
                            resources.iconUri(R.drawable.artist),
                            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                        )
                    AndroidAutoSection.ALBUMS ->
                        carBrowsableMediaItem(
                            MusicService.ALBUM,
                            resources.string(R.string.albums),
                            null,
                            resources.iconUri(R.drawable.album),
                            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                        )
                    AndroidAutoSection.PLAYLISTS ->
                        carBrowsableMediaItem(
                            MusicService.PLAYLIST,
                            resources.string(R.string.playlists),
                            null,
                            resources.iconUri(R.drawable.queue_music),
                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                        )
                }
            }

    /** Whatever the cache holds right now, possibly nothing. Never suspends. */
    fun stationItems(): List<MediaItem> {
        return CarStations.items(resources, stations.snapshot())
    }

    /** Greyed: neither something to open nor to play. */
    private fun familyItems(): List<MediaItem> =
        listOf(
            MediaItem
                .Builder()
                .setMediaId("${MusicService.CAR_FAMILY}/sangat_soon")
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(resources.string(R.string.car_family_sangat_soon))
                        .setArtworkUri(resources.iconUri(R.drawable.group_outlined))
                        .setIsPlayable(false)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                ).build(),
        )
}

/**
 * How many station rows the car should now expect, each time the Stations folder has something new to show: once
 * the cache is loaded (even if it turned out to hold nothing, so a car that was told "empty" while it loaded asks
 * again), and whenever the list the car would see changes after that. Says nothing while the cache has not loaded,
 * so an empty list that only means "not read yet" is never announced as "no stations".
 */
internal fun carStationsChanges(cache: StationsCache): Flow<Int> =
    combine(cache.loaded, cache.stations) { loaded, list -> if (loaded) CarStations.playable(list) else null }
        .filterNotNull()
        .distinctUntilChanged()
        .map { it.size }

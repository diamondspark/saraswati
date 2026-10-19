/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants.EXTRAS_KEY_IS_EXPLICIT
import androidx.media3.session.MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoSearchLocalLimitKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.get
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject
import com.metrolist.music.constants.AndroidAutoSectionsOrderKey
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.saraswati.car.CarBrowseTree
import com.metrolist.music.saraswati.car.CarResources
import com.metrolist.music.saraswati.car.CarStations
import com.metrolist.music.saraswati.car.carBrowsableMediaItem
import com.metrolist.music.saraswati.car.carDrawableUri
import com.metrolist.music.saraswati.culture.radioEndpointFor
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.security.AndroidPackageLookup
import com.metrolist.music.saraswati.security.CallerInfoFactory
import com.metrolist.music.saraswati.security.ControllerPolicy
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.deserializeSections
import com.metrolist.music.ui.screens.settings.serializeSections
import kotlinx.coroutines.withContext

class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
    // SARASWATI: Task 11 — the family stations the car's Stations tab shows.
    stationsCache: StationsCache,
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(Dispatchers.Main) + Job()

    // SARASWATI: Task 11 — the car's four tabs (Home, Library, Stations, Family). The root and each tab are
    // answered by the tree; everything below them is still answered here, as before.
    private val carTree =
        CarBrowseTree(
            resources = CarResources.of(context),
            stations = stationsCache,
            librarySections = {
                deserializeSections(
                    context.dataStore.get(
                        AndroidAutoSectionsOrderKey,
                        serializeSections(AndroidAutoSection.values().map { it to true }),
                    ),
                )
            },
            homeShelves = ::loadHomeShelves,
        )
    private val packageLookup = AndroidPackageLookup(context)
    private val tag = "MediaLibrarySessionCallback"
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}
    var addToTargetPlaylist: () -> Unit = {}

    fun release() {
        scope.cancel()
    }

    // SARASWATI: shared gate for onConnect and onGetLibraryRoot (audit
    // NEW-4). Verifies the controller's claimed package name against its
    // uid (CallerInfoFactory), then applies ControllerPolicy's allow
    // rules; logs every denial at WARN so a real Android Auto / Bluetooth
    // / Assistant connection that gets refused is visible immediately
    // (Review Focus #1).
    private fun isCallerAllowed(controller: MediaSession.ControllerInfo): Boolean {
        val caller =
            CallerInfoFactory.create(
                controllerPackage = controller.packageName,
                controllerUid = controller.uid,
                isTrusted = controller.isTrusted,
                lookup = packageLookup,
            )
        val allowed = ControllerPolicy.isAllowed(context.packageName, caller, ControllerPolicy.ANDROID_AUTO_DIGESTS)
        if (!allowed) {
            val reason =
                if (caller == null) {
                    "package name not verified"
                } else {
                    "not own package, trusted, Android Auto or system app"
                }
            // SARASWATI (I4): android.util.Log.w, not Timber — Timber.DebugTree is gated
            // behind BuildConfig.DEBUG (Task 17), so a Timber call here would be silent in
            // the release build family phones actually run, defeating the whole point of
            // this line (`grep "ControllerPolicy denied"` proving zero denials during
            // Android Auto testing). android.util.Log.w/.e are kept by proguard-rules.pro
            // in release (verified: only Log.v/.d are stripped). Only the package name and
            // a short reason category are logged — never the uid or anything sensitive.
            android.util.Log.w(tag, "ControllerPolicy denied pkg=${controller.packageName} reason=$reason")
        }
        return allowed
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        if (!isCallerAllowed(controller)) {
            return MediaSession.ConnectionResult.reject()
        }
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(MediaSessionConstants.CommandAddToTargetPlaylist)
                .build(),
            connectionResult.availablePlayerCommands,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.shuffleModeEnabled =
                !session.player.shuffleModeEnabled

            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> addToTargetPlaylist()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> =
        Futures.immediateFuture(
            MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET),
        )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (!isCallerAllowed(browser)) {
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED, params))
        }
        return Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                params,
            ),
        )
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        Futures.immediateFuture(
            if (isBrowsableMediaId(parentId)) {
                LibraryResult.ofVoid(params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            },
        )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            try {
                // SARASWATI: Task 11 — the root and the four tabs. Checked first: Stations and Family are
                // answered from the cache at once and must not wait behind a database or network lookup.
                carTree.childrenOf(parentId)?.let { children ->
                    return@future LibraryResult.ofItemList(children.paginate(page, pageSize), params)
                }

                loadLocalChildren(parentId, page, pageSize)?.let { children ->
                    return@future LibraryResult.ofItemList(children, params)
                }

                val children = when (parentId) {
                    else ->
                        when {
                            parentId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/") -> {
                                val playlistId = parentId.removePrefix("${MusicService.YOUTUBE_PLAYLIST}/")
                                try {
                                    val songs = YouTube.playlist(playlistId).getOrNull()?.songs
                                        ?.take(100)
                                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        ?: emptyList()

                                    // Add shuffle item at the top
                                    listOf(
                                        MediaItem.Builder()
                                            .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(context.getString(R.string.shuffle))
                                                    .setArtworkUri(drawableUri(R.drawable.shuffle))
                                                    .setIsPlayable(true)
                                                    .setIsBrowsable(false)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                    .build()
                                            ).build()
                                    ) + songs.map { songItem ->
                                        MediaItem.Builder()
                                            .setMediaId("$parentId/${songItem.id}")
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(songItem.title)
                                                    .setExtras(Bundle().apply {
                                                        if (songItem.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                                                    })
                                                    .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                                        ArtistNameAliases.resolve(it.id, it.name)
                                                    })
                                                    .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                                        ArtistNameAliases.resolve(it.id, it.name)
                                                    })
                                                    .setArtworkUri(songItem.thumbnail.toUri())
                                                    .setIsPlayable(true)
                                                    .setIsBrowsable(false)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                    .build()
                                            )
                                            .build()
                                    }
                                } catch (e: Exception) {
                                    reportException(e)
                                    emptyList()
                                }
                            }

                            else -> emptyList()
                        }
                }
                LibraryResult.ofItemList(children.paginate(page, pageSize), params)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }

    // SARASWATI: Task 11 — what the "Mixes" folder (MusicService.YOUTUBE_PLAYLIST) used to hold, moved here
    // unchanged except that the setting that used to switch it on is no longer read: it is the car's Home tab,
    // always offered. Failures still give an empty Home, never an error.
    private suspend fun loadHomeShelves(): List<MediaItem> =
        try {
            val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
            var continuation: String? = null
            val maxPages = 4

            for (page in 0 until maxPages) {
                val result = YouTube.home(continuation)
                    .onFailure { reportException(it) }
                    .getOrNull() ?: break
                allSections.addAll(result.sections)
                continuation = result.continuation
                if (continuation == null) break
            }

            // Drop playlists already saved to the local library,
            // which are exposed under MusicService.PLAYLIST.
            val savedBrowseIds = database.bookmarkedPlaylistBrowseIds().toSet()

            val playlists = allSections
                .flatMap { it.items }
                .filterIsInstance<PlaylistItem>()
                .filterNot { it.id in savedBrowseIds }
                .distinctBy { it.id }

            playlists.map { playlist ->
                browsableMediaItem(
                    "${MusicService.YOUTUBE_PLAYLIST}/${playlist.id}",
                    playlist.title,
                    playlist.author?.name,
                    playlist.thumbnail?.toUri(),
                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                )
            }
        } catch (e: Exception) {
            reportException(e)
            emptyList()
        }

    private suspend fun loadLocalChildren(
        parentId: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem>? {
        val request = androidAutoPageRequest(page, pageSize)
        return when {
            parentId == MusicService.ARTIST ->
                database.artistsByCreateDateAsc(request.limit, request.offset).map { artist ->
                    browsableMediaItem(
                        "${MusicService.ARTIST}/${artist.id}",
                        ArtistNameAliases.resolve(artist.id, artist.artist.name),
                        context.resources.getQuantityString(
                            R.plurals.n_song,
                            artist.songCount,
                            artist.songCount,
                        ),
                        artist.artist.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ARTIST,
                    )
                }

            parentId == MusicService.ALBUM ->
                database.albumsByCreateDateAsc(request.limit, request.offset).map { album ->
                    browsableMediaItem(
                        "${MusicService.ALBUM}/${album.id}",
                        album.album.title,
                        album.artists.joinToString {
                            ArtistNameAliases.resolve(it.id, it.name)
                        },
                        album.album.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ALBUM,
                    )
                }

            parentId == MusicService.PLAYLIST ->
                loadPlaylistContainers(request)

            parentId == MusicService.SONG ->
                database.songsByCreateDateAsc(request.limit, request.offset)
                    .map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.ARTIST}/") ->
                database.artistSongsByCreateDateAsc(
                    parentId.removePrefix("${MusicService.ARTIST}/"),
                    request.limit,
                    request.offset,
                ).map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.ALBUM}/") ->
                database.albumSongs(
                    parentId.removePrefix("${MusicService.ALBUM}/"),
                    request.limit,
                    request.offset,
                ).map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.PLAYLIST}/") ->
                loadPlaylistChildren(parentId, request)

            else -> null
        }
    }

    private suspend fun loadPlaylistContainers(request: AndroidAutoPageRequest): List<MediaItem> {
        val builtInItems =
            if (request.offset < 2 && request.limit > 0) {
                val likedSongCount = database.likedSongsCount().first()
                val downloadedSongCount = downloadUtil.downloads.value.size
                listOf(
                    browsableMediaItem(
                        "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                        context.getString(R.string.liked_songs),
                        context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                        drawableUri(R.drawable.favorite),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                    browsableMediaItem(
                        "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                        context.getString(R.string.downloaded_songs),
                        context.resources.getQuantityString(
                            R.plurals.n_song,
                            downloadedSongCount,
                            downloadedSongCount,
                        ),
                        drawableUri(R.drawable.download),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                ).drop(request.offset).take(request.limit)
            } else {
                emptyList()
            }
        val playlistRequest = request.afterLeadingItems(2)
        val playlists =
            if (playlistRequest.limit == 0) {
                emptyList()
            } else {
                database.playlistsByCreateDateAsc(playlistRequest.limit, playlistRequest.offset)
            }
        return builtInItems + playlists.map { playlist ->
            browsableMediaItem(
                "${MusicService.PLAYLIST}/${playlist.id}",
                playlist.playlist.name,
                context.resources.getQuantityString(R.plurals.n_song, playlist.songCount, playlist.songCount),
                playlist.thumbnails.firstOrNull()?.toUri(),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )
        }
    }

    private suspend fun loadPlaylistChildren(
        parentId: String,
        request: AndroidAutoPageRequest,
    ): List<MediaItem> {
        val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")
        val includeShuffle = request.offset == 0 && request.limit > 0
        val songRequest = request.afterLeadingItems(1)
        val songs = when {
            songRequest.limit == 0 -> emptyList()
            playlistId == PlaylistEntity.LIKED_PLAYLIST_ID ->
                database.likedSongsByCreateDateDesc(songRequest.limit, songRequest.offset)

            playlistId == PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                val downloads = downloadUtil.downloads.value
                val completedSongIds = downloads.entries
                    .asSequence()
                    .filter { it.value.state == Download.STATE_COMPLETED }
                    .sortedBy { it.value.updateTimeMs }
                    .map { it.key }
                    .toList()
                val existingSongIds = completedSongIds
                    .chunked(MAX_ANDROID_AUTO_PAGE_SIZE)
                    .flatMapTo(mutableSetOf()) { database.existingSongIds(it) }
                val songIds = completedSongIds.asSequence()
                    .filter(existingSongIds::contains)
                    .drop(songRequest.offset)
                    .take(songRequest.limit)
                    .toList()
                val songsById = database.getSongsByIds(songIds).associateBy { it.id }
                songIds.mapNotNull(songsById::get)
            }

            else ->
                database.playlistSongs(playlistId, songRequest.limit, songRequest.offset)
                    .map { it.song }
        }

        return buildList {
            if (includeShuffle) {
                add(
                    MediaItem.Builder()
                        .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(context.getString(R.string.shuffle))
                                .setArtworkUri(drawableUri(R.drawable.shuffle))
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        ).build()
                )
            }
            addAll(songs.map { it.toMediaItem(parentId) })
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            try {
                database.song(mediaId).first()?.toMediaItem()?.let {
                    LibraryResult.ofItem(it, null)
                } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 1, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList(), params)
            }

            try {
                val searchResults = mutableListOf<MediaItem>()
                val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)

                val allLocalSongs = database.searchSongsExtended(query, limit).first()
                allLocalSongs.forEach { song ->
                    searchResults.add(song.toMediaItem(
                        path = "${MusicService.SEARCH}/$query",
                        isPlayable = true,
                        isBrowsable = false,
                    ))
                }

                try {
                    val onlineResults = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<SongItem>()
                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                        ?.filter { onlineSong ->
                            !allLocalSongs.any { localSong ->
                                localSong.id == onlineSong.id ||
                                (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                 localSong.artists.any { artist ->
                                     onlineSong.artists.any {
                                         it.name.equals(artist.name, ignoreCase = true)
                                     }
                                 })
                            }
                        } ?: emptyList()

                    onlineResults.forEach { songItem ->
                        try {
                            database.query { insert(songItem.toMediaMetadata()) }
                        } catch (e: Exception) {
                        }
                        
                        searchResults.add(
                            MediaItem.Builder()
                                .setMediaId("${MusicService.SEARCH}/$query/${songItem.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(songItem.title)
                                        .setExtras(Bundle().apply {
                                            if (songItem.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                                        })
                                        .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                            ArtistNameAliases.resolve(it.id, it.name)
                                        })
                                        .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                            ArtistNameAliases.resolve(it.id, it.name)
                                        })
                                        .setArtworkUri(songItem.thumbnail.toUri())
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                )
                                .build()
                        )
                    }
                } catch (e: Exception) {
                    reportException(e)
                }
                
                LibraryResult.ofItemList(searchResults, params)
                
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future(Dispatchers.IO) {
            val defaultResult = MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val voiceQuery = mediaItems.firstOrNull()?.requestMetadata?.searchQuery

            val path = if (!voiceQuery.isNullOrBlank()) {
                listOf(MusicService.SEARCH, voiceQuery, "")
            } else {
                mediaItems.firstOrNull()?.mediaId?.split("/")
            } ?: return@future defaultResult

            when (path.firstOrNull()) {
                MusicService.SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val allSongs = database.songsByCreateDateAsc().first()
                    MediaItemsWithStartPosition(
                        allSongs.map { it.toMediaItem() },
                        allSongs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs
                    )
                }

                MusicService.ARTIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val artistId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = database.artistSongsByCreateDateAsc(artistId).first()
                    MediaItemsWithStartPosition(
                        songs.map { it.toMediaItem() },
                        songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs
                    )
                }

                MusicService.ALBUM -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val albumId = path.getOrNull(1) ?: return@future defaultResult
                    val albumWithSongs = database.albumWithSongs(albumId).first() ?: return@future defaultResult
                    MediaItemsWithStartPosition(
                        albumWithSongs.songs.map { it.toMediaItem() },
                        albumWithSongs.songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs
                    )
                }

                MusicService.PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = when (playlistId) {
                        PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, descending = true)
                        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                            val downloads = downloadUtil.downloads.value
                            database
                                .allSongs()
                                .flowOn(Dispatchers.IO)
                                .map { songs ->
                                    songs.filter {
                                        downloads[it.id]?.state == Download.STATE_COMPLETED
                                    }
                                }.map { songs ->
                                    songs
                                        .map { it to downloads[it.id] }
                                        .sortedBy { it.second?.updateTimeMs ?: 0L }
                                        .map { it.first }
                                }
                        }
                        else -> database.playlistSongs(playlistId).map { list ->
                            list.map { it.song }
                        }
                    }.first()

                    // Check if this is a shuffle action
                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled().map { it.toMediaItem() },
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs.map { it.toMediaItem() },
                            songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                            startPositionMs
                        )
                    }
                }

                MusicService.YOUTUBE_PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult

                    val songs = try {
                        YouTube.playlist(playlistId).getOrNull()?.songs?.map {
                            it.toMediaItem()
                        } ?: emptyList()
                    } catch (e: Exception) {
                        reportException(e)
                        return@future defaultResult
                    }

                    // Check if this is a shuffle action
                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled(),
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs,
                            songs.indexOfFirst { it.mediaId.endsWith(songId) }.takeIf { it != -1 } ?: 0,
                            C.TIME_UNSET
                        )
                    }
                }

                // SARASWATI: Task 11 — a station chosen in the car's Stations tab: the radio of the seed video the
                // row's id carries (CarStations.seedFrom checks the id before it is believed). Built the way a Saral
                // tile builds it. A station is a radio whatever the "auto radio" setting says.
                MusicService.CAR_STATIONS -> {
                    val seed = CarStations.seedFrom(mediaItems.first().mediaId) ?: return@future defaultResult
                    val radioQueue = YouTubeQueue(radioEndpointFor(seed))
                    val radioStatus = runCatching {
                        withContext(Dispatchers.IO) {
                            radioQueue
                                .getInitialStatus()
                                .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                        }
                    }.getOrNull()
                    if (radioStatus == null || radioStatus.items.isEmpty()) return@future defaultResult
                    withContext(Dispatchers.Main) {
                        service.adoptQueue(radioQueue, radioStatus.title, radioStatus.items.size)
                    }
                    MediaItemsWithStartPosition(radioStatus.items, 0, C.TIME_UNSET)
                }

                MusicService.SEARCH -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val searchQuery = path.getOrNull(1) ?: return@future defaultResult

                    val isVoiceSearch = songId.isBlank() && searchQuery.isNotBlank()

                    if (isVoiceSearch) {
                        //Search if the voiceQuery is about a local playlist and play only the songs in that playlist
                        val localPlaylists = database.searchPlaylists(searchQuery).first()
                        val exactLocalPlaylist = localPlaylists.firstOrNull {
                            it.playlist.name.equals(searchQuery, ignoreCase = true)
                        }
                        if (exactLocalPlaylist != null) {
                            val playlistSongs = database.playlistSongs(exactLocalPlaylist.playlist.id).first()
                            if (playlistSongs.isNotEmpty()) {
                                return@future MediaItemsWithStartPosition(
                                    playlistSongs.map { it.song.toMediaItem() },
                                    0,
                                    C.TIME_UNSET
                                )
                            }
                        }
                    }

                    val searchResults = mutableListOf<Song>()
                    val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)

                    val allLocalSongs = database.searchSongsExtended(searchQuery, limit).first()
                    searchResults.addAll(allLocalSongs)
                    if (!isVoiceSearch && songId.isNotBlank() && searchResults.indexOfFirst { it.id == songId } == -1) {
                        database.song(songId).first()?.let { searchResults.add(it) }
                    }
                    
                    try {
                        val onlineResults = YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_SONG)
                            .getOrNull()
                            ?.items
                            ?.filterIsInstance<SongItem>()
                            ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            ?.filter { onlineSong ->
                                !allLocalSongs.any { localSong ->
                                    localSong.id == onlineSong.id ||
                                    (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                     localSong.artists.any { artist ->
                                         onlineSong.artists.any {
                                             it.name.equals(artist.name, ignoreCase = true)
                                         }
                                     })
                                }
                            } ?: emptyList()

                        onlineResults.forEach { songItem ->
                            try {
                                database.query { insert(songItem.toMediaMetadata()) }
                                database.song(songItem.id).first()?.let { newSong ->
                                    searchResults.add(newSong)
                                }
                            } catch (e: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                    
                    if (searchResults.isEmpty()) {
                        return@future defaultResult
                    }

                    val selectedSong =
                        if (isVoiceSearch) {    //Check if the voiceQuery is about a specific song
                            val snapshot: List<Song> =
                                synchronized(searchResults) { searchResults.toList() }
                            VoiceSearchMatcher.findBest(searchQuery, snapshot)
                        } else {
                            searchResults.firstOrNull { it.id == songId }
                        }

                    if(context.dataStore.get(AutoRadioQueueKey, true)) {
                        val radioQueue = YouTubeQueue.radio(selectedSong?.toMediaMetadata() ?: return@future defaultResult)
                        val radioStatus = runCatching {
                            withContext(Dispatchers.IO) {
                                radioQueue
                                    .getInitialStatus()
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            }
                        }.getOrNull()

                        if (radioStatus != null && radioStatus.items.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                service.adoptQueue(radioQueue, radioStatus.title, radioStatus.items.size) //Used to make the radio queue load more songs when near the end
                            }
                            return@future MediaItemsWithStartPosition(
                                radioStatus.items,
                                radioStatus.items.indexOfFirst { it.mediaId == selectedSong.id }.coerceAtLeast(0),
                                C.TIME_UNSET,
                            )
                        }
                    }

                    val items = listOf(selectedSong?.toMediaItem() ?: return@future defaultResult)
                    withContext(Dispatchers.Main) {
                        service.adoptQueue(
                            ListQueue(
                                title = selectedSong.song.title,
                                items = items,
                            ),
                            title = selectedSong.song.title,
                        )
                    }
                    MediaItemsWithStartPosition(
                        items,
                        0,
                        C.TIME_UNSET
                    )
                }

                else -> defaultResult
            }
        }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = carDrawableUri(context, id)

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = carBrowsableMediaItem(id, title, subtitle, iconUri, mediaType)

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false): MediaItem {
        return MediaItem
            .Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                 MediaMetadata
                     .Builder()
                     .setTitle(song.title)
                     .setExtras(Bundle().apply {
                         if (song.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                     })
                     .setSubtitle(artists.joinToArtistString(getArtistSeparator(context)) {
                         ArtistNameAliases.resolve(it.id, it.name)
                     })
                     .setArtist(artists.joinToArtistString(getArtistSeparator(context)) {
                         ArtistNameAliases.resolve(it.id, it.name)
                     })
                     .setArtworkUri(song.thumbnailUrl?.toUri())
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }
}

internal fun isBrowsableMediaId(mediaId: String): Boolean =
    mediaId == MusicService.ROOT ||
        mediaId == MusicService.SONG ||
        mediaId == MusicService.ARTIST ||
        mediaId == MusicService.ALBUM ||
        mediaId == MusicService.PLAYLIST ||
        mediaId == MusicService.YOUTUBE_PLAYLIST ||
        // SARASWATI: Task 11 — the car's four tabs.
        mediaId == MusicService.CAR_HOME ||
        mediaId == MusicService.CAR_LIBRARY ||
        mediaId == MusicService.CAR_STATIONS ||
        mediaId == MusicService.CAR_FAMILY ||
        mediaId.startsWith("${MusicService.ARTIST}/") ||
        mediaId.startsWith("${MusicService.ALBUM}/") ||
        mediaId.startsWith("${MusicService.PLAYLIST}/") ||
        mediaId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/")

internal fun <T> List<T>.paginate(
    page: Int,
    pageSize: Int,
): List<T> {
    val fromIndex = (page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
    val toIndex = (fromIndex.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
    return subList(fromIndex, toIndex)
}

internal const val MAX_ANDROID_AUTO_PAGE_SIZE = 500

internal data class AndroidAutoPageRequest(
    val offset: Int,
    val limit: Int,
) {
    fun afterLeadingItems(count: Int): AndroidAutoPageRequest {
        val leadingItemsOnPage = (count - offset).coerceIn(0, limit)
        return AndroidAutoPageRequest(
            offset = (offset - count).coerceAtLeast(0),
            limit = limit - leadingItemsOnPage,
        )
    }
}

internal fun androidAutoPageRequest(
    page: Int,
    pageSize: Int,
): AndroidAutoPageRequest {
    val safePage = page.coerceAtLeast(0)
    val safePageSize = pageSize.coerceAtLeast(0)
    val effectivePageSize = safePageSize.coerceAtMost(MAX_ANDROID_AUTO_PAGE_SIZE)
    return AndroidAutoPageRequest(
        offset = (safePage.toLong() * effectivePageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        limit = effectivePageSize,
    )
}

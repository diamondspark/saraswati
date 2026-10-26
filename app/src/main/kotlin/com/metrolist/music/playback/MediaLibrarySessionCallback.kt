/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 11 — imports removed from this file with the code that used them: android.content.ContentResolver
// (the icon uri builder moved to carDrawableUri in CarMediaItems.kt) and the YouTube-playlists preference's key (Home
// is no longer behind that setting). Fix wave B2 — the Home tab is served from CarHomeSnapshot. Phase 4 Task 15 — the
// Family folder, the Parivaar Mix, a family playlist and the Sangat row are chosen here too.
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
import com.metrolist.innertube.models.WatchEndpoint // SARASWATI: artist queue
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.pages.ArtistPage // SARASWATI: artist queue
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoSearchLocalLimitKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity // SARASWATI: voice candidates built outside the DB
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.metrolist.music.constants.AndroidAutoSectionsOrderKey
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.saraswati.car.CarBrowseTree
import com.metrolist.music.saraswati.car.CarLocalTree
import com.metrolist.music.saraswati.perf.sarTraceSection // SARASWATI: Phase 5 Task 12
import com.metrolist.music.saraswati.car.CarHomeHolder
import com.metrolist.music.saraswati.car.CarHomeSnapshot
import com.metrolist.music.saraswati.car.FileCarHomeStore
import com.metrolist.music.saraswati.car.CarFamilyTree
import com.metrolist.music.saraswati.car.CarResources
import com.metrolist.music.saraswati.car.carFamilyChanges
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixQueue
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
// SARASWATI: Phase 6 Task 7 — the car's Prahar row and its selection.
import com.metrolist.music.saraswati.craft.prahar.CarPraharRow
import com.metrolist.music.saraswati.craft.prahar.PraharQueue
import com.metrolist.music.saraswati.craft.prahar.PraharRepository
import com.metrolist.music.saraswati.craft.prahar.PraharStore
// SARASWATI: Phase 6 fix (AB-I1) — the car Prahar row leaves out songs snoozed since the mix was built.
import com.metrolist.music.saraswati.craft.snooze.SnoozeStore
import java.time.LocalDateTime
import com.metrolist.music.saraswati.family.playlists.FamilyPlaylistsRepository
import com.metrolist.music.saraswati.family.sangat.CarSangatController
import com.metrolist.music.saraswati.family.sangat.CarSangatResult
import com.metrolist.music.saraswati.family.sangat.RepeatSelectionGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import com.metrolist.music.saraswati.car.CarStations
import com.metrolist.music.saraswati.car.carBrowsableMediaItem
import com.metrolist.music.saraswati.car.carDrawableUri
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.security.AndroidPackageLookup
import com.metrolist.music.saraswati.security.CallerInfoFactory
import com.metrolist.music.saraswati.security.ControllerPolicy
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.deserializeSections
import com.metrolist.music.ui.screens.settings.serializeSections
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
    // SARASWATI: Task 11 — the family stations the car's Stations tab shows.
    stationsCache: StationsCache,
    // SARASWATI: Phase 4 Task 15 — what the car's Family folder shows, and the Sangat row's controller.
    private val parivaarMixStore: ParivaarMixStore,
    private val familyPlaylists: FamilyPlaylistsRepository,
    private val featureGate: FamilyFeatureGate,
    private val carSangat: CarSangatController,
    // SARASWATI: Phase 6 Task 7 — the Prahar mix the car's Home leads with, and its refresh on browse.
    private val praharStore: PraharStore,
    private val praharRepository: PraharRepository,
    // SARASWATI: Phase 6 fix (AB-I1) — snoozes are checked when the car plays the Prahar mix.
    private val snoozeStore: SnoozeStore,
    // SARASWATI: Phase 5 Task 6 — the car's On this phone rows; the repository honours the Local files switch.
    private val localSongs: com.metrolist.music.saraswati.localmedia.LocalSongsRepository,
) : MediaLibrarySession.Callback {
    // SARASWATI: device fix F1 — a supervisor, so one failed car request cannot cancel the rest.
    private val scope = newCarCallbackScope()

    // SARASWATI: fix wave B2 — see CarHomeSnapshot.
    private val homeSnapshot =
        CarHomeSnapshot(
            store = FileCarHomeStore.of(context),
            fetch = ::loadHomeShelves,
            scope = scope,
        ).also(CarHomeHolder::register)

    /** How many rows the car's Home tab now has, each time a background refresh brought a new one. */
    val homeChanges get() = homeSnapshot.changes

    // SARASWATI: Phase 4 Task 15 — the Family folder's rows from what the phone already holds; never waits.
    private val carResources = CarResources.of(context)
    private fun familyRows(): List<MediaItem> =
        CarFamilyTree.children(
            parivaarMixStore.cached(),
            familyPlaylists.cached(),
            featureGate::isEnabled,
            carSangat.state.value,
            carResources,
        )

    // SARASWATI: Phase 5 Task 6 — access folds in the Local files switch: switched off, the car sees an unsupported phone.
    private suspend fun carLocalAccess(): com.metrolist.music.saraswati.localmedia.LocalAccess =
        com.metrolist.music.saraswati.localmedia.LocalFiles.visibleAccess(
            com.metrolist.music.saraswati.localmedia.localAccess(context),
            localSongs.storedSwitch.first(),
        )

    private suspend fun carLibraryLocalRows(): List<MediaItem> {
        val access = carLocalAccess()
        val localCount = if (access == com.metrolist.music.saraswati.localmedia.LocalAccess.GRANTED) localSongs.count.first() else 0
        val downloadedCount = downloadUtil.downloads.value.values.count { it.state == Download.STATE_COMPLETED }
        return CarLocalTree.libraryRows(carResources, access, localCount, downloadedCount)
    }

    private suspend fun carLocalChildren(): List<MediaItem> {
        val access = carLocalAccess()
        val songs = if (access == com.metrolist.music.saraswati.localmedia.LocalAccess.GRANTED) localSongs.songs.first() else emptyList()
        return CarLocalTree.children(carResources, access, songs, includeShuffle = songs.isNotEmpty())
    }

    /** How many rows the car's Family folder now has, each time something it shows may have changed. */
    val familyChanges: Flow<Int> =
        carFamilyChanges(
            triggers =
                listOf(
                    flow {
                        parivaarMixStore.awaitHydrated()
                        emitAll(parivaarMixStore.mix)
                    },
                    familyPlaylists.playlists,
                    featureGate.enabled,
                    carSangat.state,
                ),
            items = ::familyRows,
        )

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
            // SARASWATI: fix wave B2 — Home answers from its last good snapshot at once and refreshes in the
            // background (8 s at most), so a slow or failing YouTube never leaves the car's Home tab blank.
            // SARASWATI: Phase 6 Task 7 — the Prahar row leads Home, above YouTube's unchanged shelves.
            homeShelves = { listOfNotNull(CarPraharRow.item(praharStore.cached(), LocalDateTime.now(), carResources)) + homeSnapshot.current() },
            familyItems = ::familyRows,
        )
    private val packageLookup = AndroidPackageLookup(context)
    private val tag = "MediaLibrarySessionCallback"
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}
    var addToTargetPlaylist: () -> Unit = {}

    fun release() {
        // SARASWATI: fix wave B3 — a released callback must not be what a later sign-out tries to clear.
        CarHomeHolder.unregister(homeSnapshot)
        scope.cancel()
    }

    // SARASWATI: shared gate for onConnect and onGetLibraryRoot (audit
    // NEW-4). Verifies the controller's claimed package name against its
    // uid (CallerInfoFactory), then applies ControllerPolicy's allow
    // rules; logs every denial at WARN so a real Android Auto / Bluetooth
    // / Assistant connection that gets refused is visible immediately
    // (Review Focus #1).
    private fun callerOf(controller: MediaSession.ControllerInfo) =
        CallerInfoFactory.create(
            controllerPackage = controller.packageName,
            controllerUid = controller.uid,
            isTrusted = controller.isTrusted,
            lookup = packageLookup,
        )

    // SARASWATI: final fix wave (M7) — the Sangat button is for the car only; a phone's notification or lock
    // screen must not be able to start a room that approves joins by itself.
    fun isAndroidAutoController(controller: MediaSession.ControllerInfo): Boolean =
        ControllerPolicy.isAndroidAuto(callerOf(controller), ControllerPolicy.ANDROID_AUTO_DIGESTS)

    override fun onPostConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ) {
        if (isAndroidAutoController(controller)) service.refreshCustomLayout()
    }

    private fun isCallerAllowed(controller: MediaSession.ControllerInfo): Boolean {
        val caller = callerOf(controller)
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
                .add(MediaSessionConstants.CommandToggleSangat) // SARASWATI: Phase 4 Task 15
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
            MediaSessionConstants.ACTION_TOGGLE_SANGAT -> toggleSangat() // SARASWATI: Phase 4 Task 15
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    // SARASWATI: Phase 4 Task 15 — the Sangat row and the Sangat button. The answer to the car comes at once; the
    // room takes a moment, and a failure is told in one short phrase.
    private val sangatSelection = RepeatSelectionGuard()

    private fun toggleSangat() {
        scope.launch {
            val message =
                when (carSangat.toggle()) {
                    CarSangatResult.PairRequired -> R.string.car_sangat_pair_first
                    CarSangatResult.Disabled -> R.string.car_sangat_off
                    CarSangatResult.NoConnection -> R.string.car_sangat_no_connection
                    CarSangatResult.Started, CarSangatResult.Ended -> null
                }
            message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
        }
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
        // SARASWATI: Task 12 — the whole browse is one trace section ("SarCarChildren") for the car speed budget.
        scope.future(Dispatchers.IO) { sarTraceSection("SarCarChildren") {
            try {
                // SARASWATI: Phase 6 Task 7 — car browse asks for this window's Prahar mix in the background; the row
                // is answered from what is held now, never after waiting for this.
                if (parentId == MusicService.CAR_HOME) {
                    scope.launch { praharRepository.refreshIfDue(LocalDateTime.now()) }
                }
                // SARASWATI: Task 11 — the root and the four tabs. Checked first: Stations and Family are
                // answered from the cache at once and must not wait behind a database or network lookup.
                // SARASWATI: Phase 5 Task 6 — On this phone's folder, and the Library tab's two local rows at its tail.
                if (parentId == MusicService.CAR_LOCAL) {
                    return@future LibraryResult.ofItemList(carLocalChildren().paginate(page, pageSize), params)
                }
                carTree.childrenOf(parentId)?.let { children ->
                    val rows = if (parentId == MusicService.CAR_LIBRARY) children + carLibraryLocalRows() else children
                    return@future LibraryResult.ofItemList(rows.paginate(page, pageSize), params)
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
        } }

    // SARASWATI: Task 11 — what the "Mixes" folder (MusicService.YOUTUBE_PLAYLIST) used to hold, moved here
    // unchanged except that the setting that used to switch it on is no longer read: it is the car's Home tab,
    // always offered. Fix wave B2: a failure is null (CarHomeSnapshot then keeps what it has), never an error.
    private suspend fun loadHomeShelves(): List<MediaItem>? =
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
            null
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
                val downloadedSongCount = downloadUtil.downloads.value.values.count { it.state == Download.STATE_COMPLETED }
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
                // SARASWATI: fix wave P5a C1 — a song on this phone is answered only while the Local files switch allows it.
                com.metrolist.music.saraswati.localmedia.LocalServingPolicy.lookup(
                    mediaId = mediaId,
                    canServe = localSongs::canServe,
                    find = { id -> database.song(id).first()?.toMediaItem() },
                )?.let {
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
                    // SARASWATI: Phase 4 Task 15 — a family playlist chosen in the Family folder is
                    // youtube_playlist/<id> with no song: the whole playlist, from the top.
                    if (path.size == 2) {
                        val songs = try {
                            YouTube.playlist(path[1]).getOrNull()?.songs?.map { it.toMediaItem() } ?: emptyList()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            reportException(e)
                            emptyList()
                        }
                        return@future if (songs.isEmpty()) defaultResult else MediaItemsWithStartPosition(songs, 0, C.TIME_UNSET)
                    }
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
                // SARASWATI: Phase 5 Task 6 — a song in On this phone: every local song in title order from that one, or
                // all of them shuffled. The repository gives only the songs the Local files switch allows.
                MusicService.CAR_LOCAL -> {
                    val selected = path.getOrNull(1) ?: return@future defaultResult
                    val songs = localSongs.songs.first()
                    val (items, startAt, startMs) =
                        if (selected == MusicService.SHUFFLE_ACTION) {
                            Triple(CarLocalTree.shuffleQueue(songs).map { it.toMediaItem() }, 0, C.TIME_UNSET)
                        } else {
                            val queue = CarLocalTree.queueFrom(songs, selected) ?: return@future defaultResult
                            Triple(queue.songs.map { it.toMediaItem() }, queue.startIndex, startPositionMs)
                        }
                    // SARASWATI: fix wave P5a I1 — this branch bypasses MusicService.playQueue, so the Sangat rule is
                    // applied here: in a room no song on this phone starts, and the car is told why.
                    val inRoom = service.listenTogetherManager.isInRoom
                    val allowed =
                        com.metrolist.music.saraswati.localmedia.LocalSangatPolicy.filterForRoom(
                            Queue.Status(title = null, items = items, mediaItemIndex = startAt, position = startMs),
                            inRoom,
                        )
                    if (inRoom) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, R.string.local_songs_cant_join_sangat, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (allowed == null) return@future defaultResult
                    MediaItemsWithStartPosition(allowed.items, allowed.mediaItemIndex, allowed.position)
                }

                MusicService.CAR_STATIONS -> {
                    val seed = CarStations.seedFrom(mediaItems.first().mediaId) ?: return@future defaultResult
                    // SARASWATI: Phase 5 Task 9 — offline, or when the radio fails, the station plays its saved songs.
                    val radioQueue = service.stationQueue(seed)
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

                // SARASWATI: Phase 6 Task 7 — the car's Prahar row plays the mix held for this window and day, from the
                // phone's own copy. A mix that is no longer the current window's is not played.
                CarPraharRow.MEDIA_ID -> {
                    if (path.size != 1) return@future defaultResult
                    val held = praharStore.cached()?.takeIf { CarPraharRow.isCurrent(it, LocalDateTime.now()) } ?: return@future defaultResult
                    val snoozed = snoozeStore.awaitActiveIds()
                    val mix = held.copy(tracks = held.tracks.filterNot { it.videoId in snoozed })
                    if (!CarPraharRow.isCurrent(mix, LocalDateTime.now())) return@future defaultResult
                    val queue = PraharQueue(mix)
                    val status = queue.getInitialStatus()
                    withContext(Dispatchers.Main) { service.adoptQueue(queue, status.title, status.items.size) }
                    MediaItemsWithStartPosition(status.items, 0, C.TIME_UNSET)
                }

                // SARASWATI: Phase 4 Task 15 — the Family folder's Parivaar Mix row: the last mix built, from the
                // phone's own copy (nothing is fetched), and the Sangat row: toggle Sangat and leave what is
                // playing exactly as it is.
                CarFamilyTree.PARIVAAR_MIX_ID -> {
                    if (path.size != 1 || !featureGate.isEnabled(FamilyFeature.PARIVAAR_MIX)) return@future defaultResult
                    val tracks = parivaarMixStore.cached().tracks
                    if (tracks.isEmpty()) return@future defaultResult
                    val queue = ParivaarMixQueue(tracks)
                    val status = queue.getInitialStatus()
                    withContext(Dispatchers.Main) { service.adoptQueue(queue, status.title, status.items.size) }
                    MediaItemsWithStartPosition(status.items, 0, C.TIME_UNSET)
                }

                // Phase 4 fix wave: a repeat of the selection does not toggle again, and the player's own queue is
                // never set again (that rebuffers the song, and a paused player may then start): the request ends
                // as "not supported", which media3 reports to the car without touching the player.
                CarFamilyTree.SANGAT_TOGGLE_ID -> {
                    if (path.size != 1) return@future defaultResult
                    if (sangatSelection.shouldAct()) toggleSangat()
                    throw UnsupportedOperationException("the Sangat row changes no queue")
                }

                MusicService.SEARCH -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val searchQuery = path.getOrNull(1) ?: return@future defaultResult

                    val isVoiceSearch = songId.isBlank() && searchQuery.isNotBlank()

                    if (isVoiceSearch) {
                        //Search if the voiceQuery is about a local playlist and play only the songs in that playlist
                        // SARASWATI: voice queries are matched to playlist names tolerantly ("road trip
                        // playlist" finds "Road Trip"); this used to need the exact name.
                        val exactLocalPlaylist =
                            VoiceSearchMatcher.findBestByName(searchQuery, database.playlistsByCreateDateAsc().first()) { it.playlist.name }
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
                    // SARASWATI: YouTube's own first result for the query, kept as the last resort below.
                    var topOnline: Song? = null

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

                        // SARASWATI: the read-back may not see the row yet. The insert is queued on Room's executor and does not
                        // block, so a null read is normal timing, not an error. The candidate is then built from the search result.
                        onlineResults.forEach { songItem ->
                            val cached =
                                try {
                                    database.query { insert(songItem.toMediaMetadata()) }
                                    database.song(songItem.id).first()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    reportException(e)
                                    null
                                }
                            val candidate = cached ?: songItem.toVoiceCandidate()
                            searchResults.add(candidate)
                            if (topOnline == null) topOnline = candidate
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                    
                    if (searchResults.isEmpty()) {
                        return@future defaultResult
                    }

                    val matchedSong =
                        if (isVoiceSearch) {    //Check if the voiceQuery is about a specific song
                            val snapshot: List<Song> =
                                synchronized(searchResults) { searchResults.toList() }
                            VoiceSearchMatcher.findBest(searchQuery, snapshot)
                        } else {
                            searchResults.firstOrNull { it.id == songId }
                        }

                    // SARASWATI: "play <artist>" is an artist query, not a song title, so findBest
                    // scores it below its threshold and returns null — the car then gets an empty
                    // result and says the content isn't available. Resolve the query against the
                    // candidates' artists instead and play that artist's songs.
                    if (isVoiceSearch && matchedSong == null) {
                        val snapshot: List<Song> = synchronized(searchResults) { searchResults.toList() }
                        val artist =
                            VoiceSearchMatcher.findBestArtist(
                                searchQuery,
                                snapshot.flatMap { it.artists }.distinctBy { it.id },
                            )
                        // SARASWATI: a matched artist plays from its YouTube artist page first (shuffle, play, radio, else top songs), so the
                        // queue is the catalog rather than the search page. Any failure falls through to the snapshot logic below.
                        if (artist != null) {
                            // SARASWATI: a driver is waiting in the car, so the artist page and its radio share one 8 s budget.
                            // On a weak signal the wait gives up and the songs already in memory play instead, which beats
                            // silence. A timeout is handled like any other failure here: it falls through to the snapshot logic below.
                            val fromArtistPage: MediaItemsWithStartPosition? =
                                withTimeoutOrNull(ARTIST_HOPS_TIMEOUT_MS) {
                                    val plan = artistQueuePlan(artist.id, fetchArtistPage = { YouTube.artist(it) }, onFailure = ::reportException)
                                    if (plan.queueEndpoint != null) {
                                        val radioQueue = YouTubeQueue(plan.queueEndpoint)
                                        val radioItems =
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    radioQueue
                                                        .getInitialStatus()
                                                        .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                                        .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                                        .items
                                                }
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                reportException(e)
                                                null
                                            }
                                        if (!radioItems.isNullOrEmpty()) {
                                            withContext(Dispatchers.Main) {
                                                service.adoptQueue(radioQueue, artist.name, radioItems.size)
                                            }
                                            return@withTimeoutOrNull MediaItemsWithStartPosition(radioItems, 0, C.TIME_UNSET)
                                        }
                                    }
                                    val topItems =
                                        plan.topSongs
                                            .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                            .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                            .map { it.toMediaItem() }
                                    if (topItems.isNotEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            service.adoptQueue(ListQueue(title = artist.name, items = topItems), title = artist.name)
                                        }
                                        return@withTimeoutOrNull MediaItemsWithStartPosition(topItems, 0, C.TIME_UNSET)
                                    }
                                    null
                                }
                            if (fromArtistPage != null) return@future fromArtistPage
                        }
                        val songsByArtist =
                            artist?.let { matched -> snapshot.filter { song -> song.artists.any { it.id == matched.id } } }
                        // SARASWATI: a matched artist with a thin catalog (an actor or featured credit like "Aamir Khan") also gets
                        // the songs whose title names every word of the query. A real catalog (2+ songs) is left as it was. With
                        // no artist matched, nothing changes here and the album fallback below handles the query.
                        val combined: List<Song> =
                            when {
                                artist == null -> emptyList()
                                (songsByArtist?.size ?: 0) >= 2 -> songsByArtist.orEmpty()
                                else -> {
                                    val mentionTokens = titleMentionQueryTokens(searchQuery)
                                    val titleMatches = snapshot.filter { titleMentionsAllTokens(it.song.title, mentionTokens) }
                                    ((songsByArtist ?: emptyList()) + titleMatches).distinctBy { it.id }.take(25)
                                }
                            }
                        if (artist != null && combined.isNotEmpty()) {
                            val items = combined.map { it.toMediaItem() }
                            withContext(Dispatchers.Main) {
                                service.adoptQueue(
                                    ListQueue(title = artist.name, items = items),
                                    title = artist.name,
                                )
                            }
                            return@future MediaItemsWithStartPosition(items, 0, C.TIME_UNSET)
                        }
                    }

                    // SARASWATI: "play <movie / album name>" — match the albums of the candidate songs, then play that
                    // album from the top (YouTube's full track list when it answers, else the matching candidates).
                    if (isVoiceSearch && matchedSong == null) {
                        val snapshot: List<Song> = synchronized(searchResults) { searchResults.toList() }
                        val album =
                            VoiceSearchMatcher.findBestByName(
                                searchQuery,
                                snapshot.mapNotNull { it.album }.distinctBy { it.id },
                            ) { VoiceSearchMatcher.cleanTitle(it.title) }
                        if (album != null) {
                            val remoteTracks =
                                try {
                                    YouTube.album(album.id).getOrNull()?.songs?.map { it.toMediaItem() }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    reportException(e)
                                    null
                                }
                            val items =
                                remoteTracks?.takeIf { it.isNotEmpty() }
                                    ?: snapshot.filter { it.album?.id == album.id }.map { it.toMediaItem() }
                            if (items.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    service.adoptQueue(ListQueue(title = album.title, items = items), title = album.title)
                                }
                                return@future MediaItemsWithStartPosition(items, 0, C.TIME_UNSET)
                            }
                        }
                    }

                    // SARASWATI: last resort. Nothing matched confidently as a song, artist or album, but YouTube already
                    // ranked results for exactly what was said: play its first one, as YouTube Music itself does, rather
                    // than answering "content not available". With no network, the best positively-scored local song, else
                    // the first local text match.
                    val selectedSong =
                        matchedSong
                            ?: if (isVoiceSearch) {
                                val snapshot: List<Song> = synchronized(searchResults) { searchResults.toList() }
                                topOnline
                                    ?: VoiceSearchMatcher.rankAll(searchQuery, snapshot).firstOrNull { it.score > 0.0 }?.song
                                    ?: snapshot.firstOrNull()
                            } else {
                                null
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

                        // SARASWATI: the voice-matched song always plays first, so the car starts on exactly what was asked for
                        // and the radio mix follows it. A radio mix often does not include its own seed track; the earlier
                        // guard then fell back to the seed alone, collapsing "play shahrukh khan songs" to one song.
                        val radioPlay =
                            radioStatus?.items?.let { mix ->
                                radioPlayPlan(selectedSong.id, selectedSong.toMediaItem(), mix) { it.mediaId }
                            }
                        if (radioStatus != null && radioPlay != null) {
                            withContext(Dispatchers.Main) {
                                service.adoptQueue(radioQueue, radioStatus.title, radioPlay.items.size) //Used to make the radio queue load more songs when near the end
                            }
                            return@future MediaItemsWithStartPosition(
                                radioPlay.items,
                                radioPlay.startIndex,
                                C.TIME_UNSET,
                            )
                        }
                    }

                    // SARASWATI: the genuine last resort, reached only when radio is switched off or its fetch failed or came back empty.
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

    // SARASWATI: Task 11 — the body of this helper (and of browsableMediaItem below) moved to
    // CarMediaItems.kt so the car tabs build their rows the same way; both only delegate now.
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

/**
 * The scope every car request in [MediaLibrarySessionCallback] runs on. A SupervisorJob, not a plain Job: the Sangat row
 * deliberately fails its `future` ("changes no queue"), and a plain parent would be cancelled by that child, so the toggle
 * it had just launched never ran and every later list answered "Parent job is Cancelled" until the service restarted.
 */
internal fun newCarCallbackScope(dispatcher: CoroutineDispatcher = Dispatchers.Main): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcher)

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
        mediaId == MusicService.CAR_LOCAL ||
        mediaId.startsWith("${MusicService.ARTIST}/") ||
        mediaId.startsWith("${MusicService.ALBUM}/") ||
        mediaId.startsWith("${MusicService.PLAYLIST}/") ||
        mediaId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/")

// SARASWATI: strict title-mention filter for "play <name>" when the name has no real catalog; deliberately no fuzzy matching.
internal fun titleMentionQueryTokens(query: String): Set<String> =
    query
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{Nd} ]"), "")
        .split(' ')
        .filter { it.length >= 2 }
        .toSet()

internal fun titleMentionsAllTokens(title: String, tokens: Set<String>): Boolean {
    if (tokens.isEmpty()) return false
    val lowered = title.lowercase()
    return tokens.all { lowered.contains(it) }
}

internal data class ArtistQueuePlan(
    val queueEndpoint: WatchEndpoint?,
    val topSongs: List<SongItem>,
)

private const val ARTIST_HOPS_TIMEOUT_MS = 8_000L

// SARASWATI: the search page is one page of results for the query string, so an artist's songs taken from it can never
// make a real catalog ("play sonu nigam songs" gave 3). A matched artist therefore asks YouTube for its own page: its
// shuffle (the artist's own catalog), else its play, else its radio, else its top songs. The radio comes last because
// a MIX deliberately includes similar artists, which "play <artist> songs" does not ask for. Both empty means the
// caller keeps its local fallback. Pure, so the choice is testable without the network.
internal suspend fun artistQueuePlan(
    browseId: String,
    fetchArtistPage: suspend (String) -> Result<ArtistPage>,
    onFailure: (Throwable) -> Unit = {},
): ArtistQueuePlan {
    val empty = ArtistQueuePlan(queueEndpoint = null, topSongs = emptyList())
    // Local-only artists carry generated ids that YouTube cannot browse.
    if (!browseId.startsWith("UC")) return empty
    val page: ArtistPage? =
        try {
            fetchArtistPage(browseId).onFailure(onFailure).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e)
            null
        }
    if (page == null) return empty
    return ArtistQueuePlan(
        queueEndpoint = page.artist.shuffleEndpoint ?: page.artist.playEndpoint ?: page.artist.radioEndpoint,
        topSongs = page.sections.flatMap { it.items }.filterIsInstance<SongItem>().distinctBy { it.id },
    )
}

// SARASWATI: the DB read-back can come back empty because the insert is still queued on Room's executor, so a voice
// candidate is built from the search result alone.
// Same shape as ImportSongResolver's toImportSong. A real artist id is kept (the artist-queue path needs its UC prefix);
// only a null id gets a fallback, and that fallback does not look like a YouTube id. The fallback is keyed on the artist's
// name, so one null-id artist is one artist across every song that credits them.
internal fun SongItem.toVoiceCandidate(): Song {
    val metadata = toMediaMetadata()
    return Song(
        song = metadata.toSongEntity(),
        artists =
            metadata.artists.map { artist ->
                ArtistEntity(
                    id = artist.id ?: "voice_${artist.name.trim().lowercase()}",
                    name = artist.name,
                )
            },
    )
}

// SARASWATI: the voice-search radio queue, as the list to play and the index to start on. Pure, so the choice is testable
// without the network. A seed that is in its own mix starts at its place in the mix. Otherwise the seed plays first and the
// mix follows it, so the requested song is never swapped for another song. An empty mix gives null: the caller plays the seed alone.
internal data class RadioPlay<T>(val items: List<T>, val startIndex: Int)

internal fun <T> radioPlayPlan(seedId: String, seed: T, mix: List<T>, idOf: (T) -> String): RadioPlay<T>? {
    if (mix.isEmpty()) return null
    val matchedIndex = mix.indexOfFirst { idOf(it) == seedId }
    if (matchedIndex != -1) return RadioPlay(mix, matchedIndex)
    return RadioPlay(listOf(seed) + mix.filterNot { idOf(it) == seedId }, 0)
}

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

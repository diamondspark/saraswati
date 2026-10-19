/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the logic behind the grandparents' screen.
//
// Tiles come from the StationsCache and are there on the first frame: the hub
// is asked for a fresh list in the background (on start; MainActivity does the
// same on every launch and resume) and a slow, failing or empty answer changes
// nothing. A tile starts a YouTube radio seeded from the station's video. A
// spoken song name is searched and the first song plays the way a tapped search
// result does (that song, then its radio).
//
// The player is handed in by the screen (bindPlayback) because it only exists
// once the music service connects. A tap that arrives before that is
// remembered, the last one only, and starts the moment the player is there.
//
// Fix round 1: the status line is never a promise. What Saral asked for (the
// request) and what the player is doing (SaralPlayerState) are kept apart, and
// SaralStatusRules joins them: a tap is "starting" until the player holds a
// track of that queue, then playing or paused as it really is; a failure or no
// signal is said as such; a track that is not ours drops the ring. Voice has its
// own phases: the recogniser is "in flight" until it answers, and its answer is
// accepted only then; the search that follows can be cancelled, the launch
// cannot.
package com.metrolist.music.saraswati.saral

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.saraswati.family.FamilyViewModel
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.family.StationsRefresher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/** What Saral needs from the music player. The screen adapts the app's PlayerConnection to this. */
interface SaralPlayback {
    /** What the player is doing, as far as Saral needs to say it. */
    val state: Flow<SaralPlayerState>

    /** True when the player already holds something that play/pause can resume. */
    fun hasQueue(): Boolean

    fun playRadio(seedVideoId: String)

    fun playSong(song: SongItem)

    fun togglePlayPause()

    fun skipNext()

    fun skipPrevious()
}

/**
 * Finds the song a spoken name most likely means. A success with null means no song matched; a
 * failure means the search itself did not work, which is not the same thing.
 */
interface SaralSearch {
    suspend fun firstSong(query: String): Result<SongItem?>
}

/** A station as the screen draws it: [hindiLabel] is set only for an unrenamed starting station. */
data class SaralTile(
    val station: Station,
    @StringRes val hindiLabel: Int?,
)

/** What a status line is about: a station tile, or a track named by the player. */
sealed interface SaralSubject {
    data class Station(
        val tile: SaralTile,
    ) : SaralSubject

    data class Track(
        val title: String,
    ) : SaralSubject
}

enum class VoiceNotice { NOT_FOUND, NOT_HEARD, OFFLINE, FAILED, UNAVAILABLE }

enum class VoicePhase {
    IDLE,

    /** The system speech recogniser is opening or open. Its answer is awaited; the overlay cannot be dismissed. */
    LISTENING,

    /** The recogniser answered and the song is being searched for. The overlay can be dismissed. */
    SEARCHING,
}

sealed interface SaralStatus {
    data object Idle : SaralStatus

    /** Asked for, not on the player yet. */
    data class Starting(
        val subject: SaralSubject,
    ) : SaralStatus

    data class Playing(
        val subject: SaralSubject,
    ) : SaralStatus

    data class Paused(
        val subject: SaralSubject,
    ) : SaralStatus

    data class Problem(
        val kind: PlaybackProblem,
    ) : SaralStatus

    data class Voice(
        val notice: VoiceNotice,
    ) : SaralStatus
}

/** The tile to ring: only while a station is the thing really starting, playing or paused. */
fun SaralStatus.stationId(): String? {
    val subject =
        when (this) {
            is SaralStatus.Starting -> subject
            is SaralStatus.Playing -> subject
            is SaralStatus.Paused -> subject
            else -> null
        }
    return (subject as? SaralSubject.Station)?.tile?.station?.id
}

/** What Saral last asked the player for. */
internal sealed interface SaralRequest {
    data class Station(
        val tile: SaralTile,
    ) : SaralRequest

    data class Song(
        val title: String,
    ) : SaralRequest
}

internal object SaralStatusRules {
    /** A notice for the last thing the person did, if there is one, beats everything; then a failure; then the real player. */
    fun derive(
        request: SaralRequest?,
        player: SaralPlayerState,
        notice: VoiceNotice?,
    ): SaralStatus {
        if (notice != null) return SaralStatus.Voice(notice)
        player.problem?.let { return SaralStatus.Problem(it) }
        if (request == null) {
            // Nothing was asked of Saral: report whatever the player holds, if anything.
            val title = player.title?.takeIf { player.mediaId != null } ?: return SaralStatus.Idle
            return running(SaralSubject.Track(title), player)
        }
        val subject =
            when (request) {
                is SaralRequest.Station -> SaralSubject.Station(request.tile)
                // A song's radio moves on, so the name follows the track the player really has.
                is SaralRequest.Song -> SaralSubject.Track(if (player.ours) player.title ?: request.title else request.title)
            }
        return if (player.ours) running(subject, player) else SaralStatus.Starting(subject)
    }

    private fun running(
        subject: SaralSubject,
        player: SaralPlayerState,
    ): SaralStatus = if (player.playing) SaralStatus.Playing(subject) else SaralStatus.Paused(subject)
}

/**
 * The four starting stations are named in English on the hub (the owner's editor and the car show
 * those). Saral shows the approved Hindi names for them, until the owner renames one.
 */
object SaralLabels {
    private val HINDI_BY_DEFAULT_ID: Map<String, Int> =
        mapOf(
            "default-bollywood-classics" to R.string.saral_station_bollywood,
            "default-bhajans-devotional" to R.string.saral_station_bhajan,
            "default-ghazals-old-melodies" to R.string.saral_station_ghazal,
            "default-peaceful-meditation" to R.string.saral_station_meditation,
        )

    @StringRes
    fun hindiLabelFor(station: Station): Int? {
        val label = HINDI_BY_DEFAULT_ID[station.id] ?: return null
        val startingTitle = FamilyViewModel.DEFAULT_STATIONS.firstOrNull { it.id == station.id }?.title
        return label.takeIf { station.title == startingTitle }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SaralViewModel
    internal constructor(
        private val cache: StationsCache,
        private val refresher: StationsRefresher,
        private val search: SaralSearch,
        signIn: YouTubeSignIn,
        scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(cache: StationsCache, refresher: StationsRefresher, search: SaralSearch, signIn: YouTubeSignIn) :
            this(cache, refresher, search, signIn, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        private val playback = MutableStateFlow<SaralPlayback?>(null)
        private var pending: ((SaralPlayback) -> Unit)? = null
        private var voiceJob: Job? = null
        private var recognizerInFlight = false

        private val request = MutableStateFlow<SaralRequest?>(null)

        // True once the player has shown a track of the current request's queue; after that, losing it means
        // something else took over.
        private var adopted = false
        private val notice = MutableStateFlow<VoiceNotice?>(null)

        val tiles: StateFlow<List<SaralTile>> =
            cache.stations
                .map { list -> list.map(::tileFor) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, cache.snapshot().map(::tileFor))

        private val playerState: StateFlow<SaralPlayerState> =
            playback
                .flatMapLatest { it?.state ?: flowOf(SaralPlayerState()) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, SaralPlayerState())

        val status: StateFlow<SaralStatus> =
            combine(request, playerState, notice) { asked, player, said -> SaralStatusRules.derive(asked, player, said) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, SaralStatus.Idle)

        val isPlaying: StateFlow<Boolean> =
            playerState.map { it.playing }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

        private val _voice = MutableStateFlow(VoicePhase.IDLE)
        val voice: StateFlow<VoicePhase> = _voice.asStateFlow()

        /** Starts true so a signed-in phone never sees the sign-in tile flash while storage is read. */
        val signedIn: StateFlow<Boolean> =
            signIn.signedIn.stateIn(viewModelScope, SharingStarted.Eagerly, true)

        private val _signingIn = MutableStateFlow(false)
        val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

        init {
            viewModelScope.launch { playerState.collect(::onPlayerState) }
            viewModelScope.launch { signedIn.collect { if (it) _signingIn.value = false } }
            viewModelScope.launch { refresher.refresh() }
        }

        fun bindPlayback(player: SaralPlayback?) {
            playback.value = player
            if (player != null) {
                pending?.let {
                    pending = null
                    it(player)
                }
            }
        }

        fun onTile(stationId: String) {
            val tile = tiles.value.firstOrNull { it.station.id == stationId } ?: return
            startStation(tile)
        }

        fun onPlayPause() {
            notice.value = null
            val player = playback.value
            if (player != null && player.hasQueue()) {
                player.togglePlayPause()
            } else {
                tiles.value.firstOrNull()?.let(::startStation)
            }
        }

        fun onNext() {
            notice.value = null
            playback.value?.skipNext()
        }

        fun onPrevious() {
            notice.value = null
            playback.value?.skipPrevious()
        }

        /** True if the screen should launch the recogniser now; false if one is already open. */
        fun onMicTapped(): Boolean {
            if (_voice.value != VoicePhase.IDLE) return false
            voiceJob?.cancel()
            notice.value = null
            recognizerInFlight = true
            _voice.value = VoicePhase.LISTENING
            return true
        }

        /** The person tapped the overlay. It only counts once the recogniser has answered and the search is running. */
        fun onOverlayDismissed() {
            if (_voice.value != VoicePhase.SEARCHING) return
            voiceJob?.cancel()
            _voice.value = VoicePhase.IDLE
        }

        /** The phone has no speech service to launch. */
        fun onVoiceUnavailable() {
            if (!recognizerInFlight) return
            recognizerInFlight = false
            _voice.value = VoicePhase.IDLE
            notice.value = VoiceNotice.UNAVAILABLE
        }

        fun onVoiceOutcome(outcome: VoiceOutcome) {
            if (!recognizerInFlight) return
            recognizerInFlight = false
            when (outcome) {
                VoiceOutcome.Cancelled -> _voice.value = VoicePhase.IDLE
                VoiceOutcome.NotHeard -> endVoice(VoiceNotice.NOT_HEARD)
                VoiceOutcome.Offline -> endVoice(VoiceNotice.OFFLINE)
                VoiceOutcome.Failed -> endVoice(VoiceNotice.FAILED)
                is VoiceOutcome.Heard -> searchFor(outcome.guesses)
            }
        }

        fun onSignInTapped() {
            if (!signedIn.value) _signingIn.value = true
        }

        fun onSignInClosed() {
            _signingIn.value = false
        }

        private fun endVoice(result: VoiceNotice) {
            _voice.value = VoicePhase.IDLE
            notice.value = result
        }

        /** [guesses] are the recogniser's, best first. */
        private fun searchFor(guesses: List<String>?) {
            val queries =
                guesses
                    .orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(MAX_GUESSES)
            if (queries.isEmpty()) {
                endVoice(VoiceNotice.NOT_HEARD)
                return
            }
            _voice.value = VoicePhase.SEARCHING
            voiceJob =
                viewModelScope.launch {
                    var found: SongItem? = null
                    var failure: Throwable? = null
                    for (query in queries) {
                        val answer = search.firstSong(query)
                        answer.onSuccess { found = it }.onFailure { failure = it }
                        if (found != null || failure != null) break
                    }
                    // A search that was dismissed may still hand back a (wrapped) failure; it must not speak.
                    coroutineContext.ensureActive()
                    _voice.value = VoicePhase.IDLE
                    val song = found
                    val error = failure
                    when {
                        song != null -> startSong(song)
                        error != null -> notice.value = if (PlaybackFailures.isOffline(error)) VoiceNotice.OFFLINE else VoiceNotice.FAILED
                        else -> notice.value = VoiceNotice.NOT_FOUND
                    }
                }
        }

        private fun startStation(tile: SaralTile) {
            val seed = tile.station.seedVideoId
            if (seed.isBlank()) return
            notice.value = null
            begin(SaralRequest.Station(tile))
            whenPlayerReady { it.playRadio(seed) }
        }

        private fun startSong(song: SongItem) {
            notice.value = null
            begin(SaralRequest.Song(song.title))
            whenPlayerReady { it.playSong(song) }
        }

        private fun begin(next: SaralRequest) {
            adopted = false
            request.value = next
        }

        // Once the player has shown a track of the request's queue, losing it means something else took over
        // (the car, an assistant, a media button on another queue): drop the request, so its station stops being
        // ringed and the status names the track that is really playing. An unplugged player proves nothing.
        private fun onPlayerState(state: SaralPlayerState) {
            if (playback.value == null || request.value == null) return
            if (state.ours) {
                adopted = true
            } else if (adopted) {
                adopted = false
                request.value = null
            }
        }

        private fun whenPlayerReady(action: (SaralPlayback) -> Unit) {
            val player = playback.value
            if (player != null) {
                pending = null
                action(player)
            } else {
                pending = action
            }
        }

        private fun tileFor(station: Station) = SaralTile(station, SaralLabels.hindiLabelFor(station))

        private companion object {
            const val MAX_GUESSES = 3
        }
    }

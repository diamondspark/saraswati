/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the logic behind the grandparents' screen.
//
// Tiles come from the StationsCache and are there on the first frame: the hub
// is asked for a fresh list in the background (on start, and whenever the
// screen asks again) and a slow, failing or empty answer changes nothing. A
// tile starts a YouTube radio seeded from the station's video. A spoken song
// name is searched and the first song plays the way a tapped search result
// does (that song, then its radio).
//
// The player is handed in by the screen (bindPlayback) because it only exists
// once the music service connects. A tap that arrives before that is
// remembered, the last one only, and starts the moment the player is there.
package com.metrolist.music.saraswati.saral

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.saraswati.family.FamilyStore
import com.metrolist.music.saraswati.family.FamilyViewModel
import com.metrolist.music.saraswati.family.HubApi
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** What Saral needs from the music player. The screen adapts the app's PlayerConnection to this. */
interface SaralPlayback {
    val isPlaying: StateFlow<Boolean>

    /** True when the player already holds something that play/pause can resume. */
    fun hasQueue(): Boolean

    fun playRadio(seedVideoId: String)

    fun playSong(song: SongItem)

    fun togglePlayPause()

    fun skipNext()

    fun skipPrevious()
}

/** Finds the song a spoken name most likely means. null means no song matched. */
interface SaralSearch {
    suspend fun firstSong(query: String): Result<SongItem?>
}

/** A station as the screen draws it: [hindiLabel] is set only for an unrenamed starting station. */
data class SaralTile(
    val station: Station,
    @StringRes val hindiLabel: Int?,
)

sealed interface SaralStatus {
    data object Idle : SaralStatus

    data class StationPlaying(
        val tile: SaralTile,
    ) : SaralStatus

    data class SongPlaying(
        val title: String,
    ) : SaralStatus

    data object VoiceNotFound : SaralStatus

    data object VoiceUnavailable : SaralStatus
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
        private val hub: HubApi,
        private val family: FamilyStore,
        private val search: SaralSearch,
        scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(cache: StationsCache, hub: HubApi, family: FamilyStore, search: SaralSearch) :
            this(cache, hub, family, search, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        private val playback = MutableStateFlow<SaralPlayback?>(null)
        private var pending: ((SaralPlayback) -> Unit)? = null
        private var refreshJob: Job? = null
        private var voiceJob: Job? = null

        val tiles: StateFlow<List<SaralTile>> =
            cache.stations
                .map { list -> list.map(::tileFor) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, cache.snapshot().map(::tileFor))

        private val _status = MutableStateFlow<SaralStatus>(SaralStatus.Idle)
        val status: StateFlow<SaralStatus> = _status.asStateFlow()

        private val _voiceActive = MutableStateFlow(false)

        /** True from the microphone tap until the spoken name has been searched and played (or given up on). */
        val voiceActive: StateFlow<Boolean> = _voiceActive.asStateFlow()

        val isPlaying: StateFlow<Boolean> =
            playback
                .flatMapLatest { it?.isPlaying ?: flowOf(false) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        init {
            refresh()
        }

        /** Asks the hub for the current station list in the background. Whatever happens, the tiles stay. */
        fun refresh() {
            if (refreshJob?.isActive == true) return
            refreshJob =
                viewModelScope.launch {
                    val session = guarded { family.session() } ?: return@launch
                    hub.getStations(session.sessionToken).onSuccess { fresh ->
                        guarded { cache.replace(fresh) }
                    }
                }
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
            val player = playback.value
            if (player != null && player.hasQueue()) {
                player.togglePlayPause()
            } else {
                tiles.value.firstOrNull()?.let(::startStation)
            }
        }

        fun onNext() {
            playback.value?.skipNext()
        }

        fun onPrevious() {
            playback.value?.skipPrevious()
        }

        fun onMicTapped() {
            voiceJob?.cancel()
            _voiceActive.value = true
        }

        fun onVoiceCancelled() {
            voiceJob?.cancel()
            _voiceActive.value = false
        }

        fun onVoiceUnavailable() {
            voiceJob?.cancel()
            _voiceActive.value = false
            _status.value = SaralStatus.VoiceUnavailable
        }

        /** [candidates] are the recogniser's guesses, best first. */
        fun onVoiceResult(candidates: List<String>?) {
            if (!_voiceActive.value) return
            val queries =
                candidates
                    .orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(MAX_GUESSES)
            if (queries.isEmpty()) {
                _voiceActive.value = false
                _status.value = SaralStatus.VoiceNotFound
                return
            }
            voiceJob?.cancel()
            voiceJob =
                viewModelScope.launch {
                    var found: SongItem? = null
                    for (query in queries) {
                        found = search.firstSong(query).getOrNull()
                        if (found != null) break
                    }
                    _voiceActive.value = false
                    val song = found
                    if (song == null) {
                        _status.value = SaralStatus.VoiceNotFound
                    } else {
                        _status.value = SaralStatus.SongPlaying(song.title)
                        whenPlayerReady { it.playSong(song) }
                    }
                }
        }

        private fun startStation(tile: SaralTile) {
            val seed = tile.station.seedVideoId
            if (seed.isBlank()) return
            _status.value = SaralStatus.StationPlaying(tile)
            whenPlayerReady { it.playRadio(seed) }
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

        private suspend fun <T> guarded(block: suspend () -> T): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        private companion object {
            const val MAX_GUESSES = 3
        }
    }

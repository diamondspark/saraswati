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
import com.metrolist.music.saraswati.culture.Festival
import com.metrolist.music.saraswati.culture.FestivalClock
import com.metrolist.music.saraswati.culture.FestivalRadio
import com.metrolist.music.saraswati.ask.AskVoiceExplanationGate
import com.metrolist.music.saraswati.ask.AskVoiceExplanationStore
import com.metrolist.music.saraswati.ask.SaralAction
import com.metrolist.music.saraswati.ask.SaralAsk
import com.metrolist.music.saraswati.culture.FestivalSeed
import com.metrolist.music.saraswati.family.FamilyViewModel
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.family.StationsRefresher
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixQueue
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
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/** What Saral needs from the music player. The screen adapts the app's PlayerConnection to this. */
interface SaralPlayback {
    /** What the player is doing, as far as Saral needs to say it. */
    val state: Flow<SaralPlayerState>

    /** True when the player already holds something that play/pause can resume. */
    fun hasQueue(): Boolean

    fun playRadio(seedVideoId: String)

    fun playSong(song: SongItem)

    /** The Parivaar Mix, in order, as a queue of its own. */
    fun playParivaarMix(tracks: List<MixTrack>)

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

    /** The radio of today's festival, started from the ribbon. */
    data class Festival(
        val festival: com.metrolist.music.saraswati.culture.Festival,
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

    data class Festival(
        val festival: com.metrolist.music.saraswati.culture.Festival,
    ) : SaralRequest

    data object Mix : SaralRequest
}

internal object SaralStatusRules {
    /**
     * A notice for the last thing the person did, if there is one, beats everything; then a failure
     * ([problem] is the one Saral met itself, such as a festival radio it could not find a song for,
     * and is newer than the player's); then the real player.
     */
    fun derive(
        request: SaralRequest?,
        player: SaralPlayerState,
        notice: VoiceNotice?,
        problem: PlaybackProblem? = null,
        searching: Festival? = null,
    ): SaralStatus {
        if (notice != null) return SaralStatus.Voice(notice)
        (problem ?: player.problem)?.let { return SaralStatus.Problem(it) }
        // SARASWATI: fix wave B2 (M1) — looking for a festival radio's first song is "starting" whatever the
        // player holds: a queue Saral started before is still playing, and must not be reported as the festival's.
        if (searching != null) return SaralStatus.Starting(SaralSubject.Festival(searching))
        if (request == null) {
            // Nothing was asked of Saral: report whatever the player holds, if anything.
            val title = player.title?.takeIf { player.mediaId != null } ?: return SaralStatus.Idle
            return running(SaralSubject.Track(title), player)
        }
        val subject =
            when (request) {
                is SaralRequest.Station -> SaralSubject.Station(request.tile)
                is SaralRequest.Festival -> SaralSubject.Festival(request.festival)
                is SaralRequest.Mix -> SaralSubject.Track(if (player.ours) player.title ?: ParivaarMixQueue.TITLE else ParivaarMixQueue.TITLE)
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
        festivalClock: FestivalClock = FestivalClock(),
        // SARASWATI: Phase 6 Task 10 — null keeps the voice path exactly as it was: the recogniser's words go to search.
        private val ask: SaralAsk? = null,
        // SARASWATI: security-ask-explanation — null skips the one-time explanation (treated as already seen),
        // so tests that do not care about it are unaffected.
        private val explanationGate: AskVoiceExplanationGate? = null,
        // SARASWATI: security-ask-explanation (fourth-round audit fix 2; fifth-round audit fix 1) — overridable
        // only so a test can move time without a real 600ms delay. SystemClock.elapsedRealtime(), not
        // System.currentTimeMillis(): the debounce in onOverlayDismissed compares two readings of this, and the
        // wall clock can jump backwards (NTP correction, a manual clock change, not rare on an older phone) —
        // confirmed by running it, a 1-hour backward jump would make every tap-to-cancel a no-op for an hour.
        // elapsedRealtime() is monotonic (time since boot, including sleep) and immune to that.
        private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    ) : ViewModel(scope) {
        @Inject
        constructor(
            cache: StationsCache,
            refresher: StationsRefresher,
            search: SaralSearch,
            signIn: YouTubeSignIn,
            ask: SaralAsk,
            explanationGate: AskVoiceExplanationStore,
        ) : this(
            cache,
            refresher,
            search,
            signIn,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ask = ask,
            explanationGate = explanationGate,
        )

        private val playback = MutableStateFlow<SaralPlayback?>(null)
        private var pending: ((SaralPlayback) -> Unit)? = null
        private var voiceJob: Job? = null
        private var festivalJob: Job? = null
        private val festivalRadio = FestivalRadio(search)
        private var recognizerInFlight = false

        private val request = MutableStateFlow<SaralRequest?>(null)

        // True once the player has shown a track of the current request's queue; after that, losing it means
        // something else took over.
        private var adopted = false
        private val notice = MutableStateFlow<VoiceNotice?>(null)

        // A failure Saral met itself (as opposed to one the player reports); cleared by the next thing the person does.
        private val problem = MutableStateFlow<PlaybackProblem?>(null)

        // The festival whose radio's first song is being looked for. Nothing has been asked of the player yet, so
        // this is not a request: whatever the player holds is still the previous request's (or nobody's).
        private val searchingFestival = MutableStateFlow<Festival?>(null)

        /**
         * Today's festival, if it is one. Counted from the phone's own day, and started afresh each time the screen comes
         * back (collection stops a few seconds after it leaves), so a phone that slept through midnight is right on waking.
         */
        val festival: StateFlow<Festival?> =
            festivalClock.changes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(FESTIVAL_STOP_TIMEOUT_MS), festivalClock.current())

        val tiles: StateFlow<List<SaralTile>> =
            cache.stations
                .map { list -> list.map(::tileFor) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, cache.snapshot().map(::tileFor))

        private val playerState: StateFlow<SaralPlayerState> =
            playback
                .flatMapLatest { it?.state ?: flowOf(SaralPlayerState()) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, SaralPlayerState())

        val status: StateFlow<SaralStatus> =
            combine(request, playerState, notice, problem, searchingFestival) { asked, player, said, failed, looking ->
                SaralStatusRules.derive(asked, player, said, failed, looking)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, SaralStatus.Idle)

        val isPlaying: StateFlow<Boolean> =
            playerState.map { it.playing }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

        private val _voice = MutableStateFlow(VoicePhase.IDLE)
        val voice: StateFlow<VoicePhase> = _voice.asStateFlow()

        // SARASWATI: security-ask-explanation — true while the one-time ask-voice explanation waits for the
        // person to tap Continue. The spoken words it is waiting to route are kept here, not in the request flow,
        // since nothing has been asked of the player yet.
        private val _askExplanation = MutableStateFlow(false)
        val askExplanation: StateFlow<Boolean> = _askExplanation.asStateFlow()
        private var pendingAsk: PendingAsk? = null

        // SARASWATI: security-ask-explanation (review fix 3) — once true, [explanationGate] is never read again:
        // "seen" cannot become false again, so there is nothing left to learn from another DataStore read.
        private var explanationSeenCache = false

        // SARASWATI: security-ask-explanation (fourth-round audit fix 2) — the instant the explanation closes
        // (either button), askExplanation goes false while voice can still be SEARCHING (Okay's hub call, or
        // Not now's fallback search, is still in flight): SaralScreen then draws the full-screen, tap-to-cancel
        // ListeningOverlay under the same finger that just tapped the button, and a very plausible second tap
        // reaches onOverlayDismissed() and cancels it — for Okay, after the words already left the phone, with
        // nothing to show for it. Set on every path that closes the explanation; onOverlayDismissed ignores
        // itself for a short window after. null (never closed yet) rather than a Long.MIN_VALUE sentinel: this
        // ViewModel is used for the life of the process, and nowMs() - Long.MIN_VALUE overflows and wraps
        // negative, which satisfied "< DISMISS_DEBOUNCE_MS" and wrongly debounced the very first real dismiss
        // of an ordinary voice search that never touched the explanation at all.
        private var explanationClosedAtMs: Long? = null

        private data class PendingAsk(
            val spoken: String,
            val guesses: List<String>?,
        )

        /** Starts true so a signed-in phone never sees the sign-in tile flash while storage is read. */
        val signedIn: StateFlow<Boolean> =
            signIn.signedIn.stateIn(viewModelScope, SharingStarted.Eagerly, true)

        private val _signingIn = MutableStateFlow(false)
        val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

        // True while the sign-in sheet is saving the account (LoginContent's finalizeLogin). The cookie it writes
        // turns signedIn on partway through, and closing the sheet then would cancel the rest of finalizeLogin
        // (it clears the WebView's copy of the session and restarts the app).
        private val finishingSignIn = MutableStateFlow(false)

        init {
            viewModelScope.launch { playerState.collect(::onPlayerState) }
            viewModelScope.launch {
                combine(signedIn, finishingSignIn) { signed, finishing -> signed && !finishing }
                    .collect { if (it) _signingIn.value = false }
            }
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
            clearMessages()
            val player = playback.value
            if (player != null && player.hasQueue()) {
                player.togglePlayPause()
            } else {
                tiles.value.firstOrNull()?.let(::startStation)
            }
        }

        fun onNext() {
            clearMessages()
            playback.value?.skipNext()
        }

        fun onPrevious() {
            clearMessages()
            playback.value?.skipPrevious()
        }

        /** True if the screen should launch the recogniser now; false if one is already open. */
        fun onMicTapped(): Boolean {
            if (_voice.value != VoicePhase.IDLE) return false
            voiceJob?.cancel()
            clearMessages()
            recognizerInFlight = true
            _voice.value = VoicePhase.LISTENING
            return true
        }

        /** The person tapped the overlay. It only counts once the recogniser has answered and the search is running. */
        fun onOverlayDismissed() {
            if (_askExplanation.value) {
                val pending = pendingAsk
                pendingAsk = null
                _askExplanation.value = false
                explanationClosedAtMs = nowMs()
                voiceJob?.cancel()
                // SARASWATI: security-ask-explanation (third-round audit fix 5, owner's product ruling) —
                // declining the explanation means "skip the hub for this", not "drop what I said": search the
                // spoken words the way voice search always did before Ask Saraswati existed, rather than
                // silently discarding them.
                if (pending != null) searchFor(pending.guesses) else _voice.value = VoicePhase.IDLE
                return
            }
            // SARASWATI: security-ask-explanation (fourth-round audit fix 2) — see explanationClosedAtMs: a
            // stray second tap landing on the overlay that appears the instant Okay/Not now closes the
            // explanation must not cancel the fallback search or hub call that tap just started.
            // SARASWATI: security-ask-explanation (fifth-round audit fix 1) — a negative delta (nowMs() read
            // before explanationClosedAtMs) is treated as "the window has passed", not "still inside it": the
            // clock source is now elapsedRealtime(), which cannot go backwards, but this keeps the debounce
            // from ever getting stuck if it somehow did.
            if (explanationClosedAtMs?.let { nowMs() - it in 0 until DISMISS_DEBOUNCE_MS } == true) return
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
                is VoiceOutcome.Heard -> resolveVoice(outcome.guesses)
            }
        }

        /** The ribbon's button: today's festival radio. Nothing happens if there is no festival today. */
        fun onFestivalTapped() {
            val today = festival.value ?: return
            clearMessages()
            // SARASWATI: fix wave B2 (M1) — the request begins when the seed song is found, not at the tap: until
            // then the player is still on whatever it was playing, and a request would claim that track.
            festivalJob?.cancel()
            searchingFestival.value = today
            festivalJob =
                viewModelScope.launch {
                    val seed = festivalRadio.seedFor(today)
                    // A search that was replaced may still hand back a (wrapped) failure; it must not speak.
                    coroutineContext.ensureActive()
                    when (seed) {
                        is FestivalSeed.Found -> {
                            // The request first and the searching flag off last (after the player has been handed the
                            // radio, which gives it a fresh queue watch): any other order lets a frame show the old
                            // request's status, or the old queue's track as the festival's.
                            startRequest(SaralRequest.Festival(today))
                            whenPlayerReady { it.playRadio(seed.videoId) }
                            searchingFestival.value = null
                        }
                        FestivalSeed.NotFound -> {
                            problem.value = PlaybackProblem.COULD_NOT_PLAY
                            searchingFestival.value = null
                        }
                        is FestivalSeed.Failed -> {
                            problem.value = if (seed.offline) PlaybackProblem.OFFLINE else PlaybackProblem.COULD_NOT_PLAY
                            searchingFestival.value = null
                        }
                    }
                }
        }

        fun onSignInTapped() {
            if (!signedIn.value) {
                finishingSignIn.value = false
                _signingIn.value = true
            }
        }

        fun onSignInClosed() {
            finishingSignIn.value = false
            _signingIn.value = false
        }

        /** The sign-in sheet started ([active]) or stopped saving the account. */
        fun onSignInFinishing(active: Boolean) {
            finishingSignIn.value = active
        }

        private fun endVoice(result: VoiceNotice) {
            _voice.value = VoicePhase.IDLE
            notice.value = result
        }

        /** The recogniser's first guess goes to Saral's ask; without one, or without the ask, the guesses are searched as before. */
        private fun resolveVoice(guesses: List<String>?) {
            val spoken = guesses.orEmpty().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            val asker = ask
            if (spoken == null || asker == null) {
                searchFor(guesses)
                return
            }
            _voice.value = VoicePhase.SEARCHING
            voiceJob =
                viewModelScope.launch {
                    // SARASWATI: security-ask-explanation (third-round audit fix 1; fourth-round audit fix 3) —
                    // the explanation's claim ("sends the words to a computer helper") must be true when it is
                    // shown: Ask Saraswati off, or no session, means the hub must not be asked at all. Resolving
                    // directly here, instead of runAsk (which would call asker.resolve -> repo.ask, re-checking
                    // willReachHub a second time), makes "no explanation shown -> never reaches the hub"
                    // structural rather than a timing coincidence: gate/session state flipping true between
                    // this check and a second one inside ask() could otherwise let a question through with no
                    // explanation ever having been shown.
                    //
                    // SARASWATI: security-ask-explanation (fifth-round audit fix 2) — performSearch/searchQueries
                    // directly, not searchFor: this coroutine IS voiceJob (resolveVoice assigned it below),
                    // so calling searchFor here (which does its own `voiceJob = launch { ... }`) risked that
                    // assignment being overwritten right back the moment this outer launch itself returns — see
                    // performSearch's own comment for exactly when that happens.
                    if (!asker.willReachHub()) {
                        val queries = searchQueries(guesses)
                        if (queries.isEmpty()) endVoice(VoiceNotice.NOT_HEARD) else performSearch(queries)
                        return@launch
                    }
                    val gate = explanationGate
                    // SARASWATI: security-ask-explanation (review fix 3) — once seen is true, never ask the
                    // gate again for the rest of this ViewModel's life; a DataStore read on every voice search
                    // would be wasted work once the answer can never change back to false.
                    val seen = explanationSeenCache || gate == null || gate.hasSeenSafely()
                    if (!seen) {
                        coroutineContext.ensureActive()
                        pendingAsk = PendingAsk(spoken, guesses)
                        _askExplanation.value = true
                        return@launch
                    }
                    explanationSeenCache = true
                    runAsk(asker, spoken, guesses)
                }
        }

        // SARASWATI: security-ask-explanation (third-round audit fix 6) — a DataStore read failing here must
        // default to "not seen" (the safer side: showing the explanation an extra time, not skipping a
        // disclosure it owes), the same defensive shape markSeen() already uses in onAskExplanationAcknowledged.
        private suspend fun AskVoiceExplanationGate.hasSeenSafely(): Boolean =
            try {
                hasSeen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("Saral").w(e, "failed to read whether the ask-voice explanation has been seen")
                false
            }

        /** The person tapped Continue on the one-time ask-voice explanation: mark it seen and route as originally asked. */
        fun onAskExplanationAcknowledged() {
            val pending = pendingAsk ?: return
            val asker = ask ?: return
            pendingAsk = null
            _askExplanation.value = false
            // SARASWATI: security-ask-explanation (fourth-round audit fix 2) — see explanationClosedAtMs.
            explanationClosedAtMs = nowMs()
            // The person has seen and acknowledged it now, whatever happens to the write below.
            explanationSeenCache = true
            voiceJob =
                viewModelScope.launch {
                    // SARASWATI: security-ask-explanation (review fix 2) — a DataStore write failing here must
                    // not leave the voice flow stuck in SEARCHING with no clean way out: the ask the person
                    // already asked for still runs regardless. explanationSeenCache above means this session
                    // will not show the explanation again even if the write failed; a future session would
                    // read the gate fresh and, if it is still false on disk, show it and retry the write.
                    try {
                        explanationGate?.markSeen()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag("Saral").w(e, "failed to persist the ask-voice explanation as seen")
                    }
                    coroutineContext.ensureActive()
                    runAsk(asker, pending.spoken, pending.guesses)
                }
        }

        private suspend fun runAsk(
            asker: SaralAsk,
            spoken: String,
            guesses: List<String>?,
        ) {
            val action = asker.resolve(spoken)
            coroutineContext.ensureActive()
            when (action) {
                is SaralAction.PlayStation -> {
                    val tile = tiles.value.firstOrNull { it.station.id == action.stationId }
                    if (tile == null) {
                        searchFor(listOf(spoken))
                    } else {
                        _voice.value = VoicePhase.IDLE
                        startStation(tile)
                    }
                }
                SaralAction.ParivaarMix -> {
                    val tracks = asker.cachedMixTracks()
                    if (tracks.isEmpty()) {
                        searchFor(listOf(spoken))
                    } else {
                        _voice.value = VoicePhase.IDLE
                        startMix(tracks)
                    }
                }
                is SaralAction.VoiceSearch ->
                    searchFor(if (action.text == spoken) guesses else listOf(action.text))
            }
        }

        /** [guesses] are the recogniser's, best first. */
        private fun searchFor(guesses: List<String>?) {
            val queries = searchQueries(guesses)
            if (queries.isEmpty()) {
                endVoice(VoiceNotice.NOT_HEARD)
                return
            }
            _voice.value = VoicePhase.SEARCHING
            voiceJob = viewModelScope.launch { performSearch(queries) }
        }

        private fun searchQueries(guesses: List<String>?): List<String> =
            guesses
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_GUESSES)

        // SARASWATI: security-ask-explanation (fifth-round audit fix 2) — a suspend function with no launch
        // and no voiceJob assignment of its own, so it is safe to call directly from inside a coroutine that
        // is already [voiceJob] itself (see resolveVoice's willReachHub()-false branch). Calling searchFor
        // there instead — which does its own `voiceJob = launch { ... }` — looked right but wasn't: when
        // willReachHub() returns without a real suspension (true whenever Ask Saraswati is off or there is no
        // session, since neither check ever reaches the network), the whole surrounding launch block runs
        // synchronously end to end, including searchFor's nested reassignment of voiceJob to its own new job
        // — and then the *outer* launch{} call (resolveVoice's own `voiceJob = viewModelScope.launch { ... }`)
        // finally returns and overwrites voiceJob back to that outer, now-already-finished job, silently
        // clobbering the inner job that is the one actually running the search. onOverlayDismissed's
        // voiceJob?.cancel() then cancels nothing, while the real search keeps going. Confirmed by running it.
        private suspend fun performSearch(queries: List<String>) {
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

        private fun startStation(tile: SaralTile) {
            val seed = tile.station.seedVideoId
            if (seed.isBlank()) return
            clearMessages()
            begin(SaralRequest.Station(tile))
            whenPlayerReady { it.playRadio(seed) }
        }

        private fun startMix(tracks: List<MixTrack>) {
            clearMessages()
            begin(SaralRequest.Mix)
            whenPlayerReady { it.playParivaarMix(tracks) }
        }

        private fun startSong(song: SongItem) {
            clearMessages()
            begin(SaralRequest.Song(song.title))
            whenPlayerReady { it.playSong(song) }
        }

        private fun begin(next: SaralRequest) {
            festivalJob?.cancel()
            festivalJob = null
            searchingFestival.value = null
            startRequest(next)
        }

        private fun startRequest(next: SaralRequest) {
            adopted = false
            request.value = next
        }

        private fun clearMessages() {
            notice.value = null
            problem.value = null
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
            const val FESTIVAL_STOP_TIMEOUT_MS = 5_000L

            // SARASWATI: security-ask-explanation (fourth-round audit fix 2)
            const val DISMISS_DEBOUNCE_MS = 600L
        }
    }

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the grandparents' screen, without the screen. The
// tiles come from the local cache and never wait for the hub; a tile starts a
// YouTube radio on its station's seed video; a spoken song name goes through
// the same search-then-play-with-radio path a tapped search result takes.
//
// Fix round 1: what the screen says comes from what the player is really
// doing, not from what was asked of it. A tap is "starting" until the player
// holds a track from that queue; a failure, being offline, a pause and a track
// change each show; the highlighted tile follows the queue that is really
// playing. Unconfined dispatching makes each call finish before it returns,
// because the fakes never suspend (except the gated hub and search).
package com.metrolist.music.saraswati.saral

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FakeStore
import com.metrolist.music.saraswati.family.FamilySession
import com.metrolist.music.saraswati.family.OFFLINE
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsRefresher
import com.metrolist.music.saraswati.family.hubFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

class FakePlayback : SaralPlayback {
    val calls = CopyOnWriteArrayList<String>()
    val playerState = MutableStateFlow(SaralPlayerState())
    var queued = false

    override val state: Flow<SaralPlayerState> = playerState

    override fun hasQueue() = queued

    override fun playRadio(seedVideoId: String) {
        calls.add("radio($seedVideoId)")
        queued = true
    }

    override fun playSong(song: SongItem) {
        calls.add("song(${song.id})")
        queued = true
    }

    override fun togglePlayPause() {
        calls.add("toggle")
    }

    override fun skipNext() {
        calls.add("next")
    }

    override fun skipPrevious() {
        calls.add("previous")
    }

    /** The player has picked up what Saral started: [title] is on and belongs to it. */
    fun playingOurs(
        title: String,
        playing: Boolean = true,
    ) {
        playerState.value = SaralPlayerState(mediaId = "m-$title", title = title, playing = playing, ours = true)
    }

    /** Something else started: [title] is on and Saral did not ask for it. */
    fun playingSomethingElse(
        title: String,
        playing: Boolean = true,
    ) {
        playerState.value = SaralPlayerState(mediaId = "m-$title", title = title, playing = playing, ours = false)
    }
}

class FakeSearch : SaralSearch {
    val queries = CopyOnWriteArrayList<String>()
    val answers = HashMap<String, Result<SongItem?>>()

    /** While set, every search waits on it: a search still in flight. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    override suspend fun firstSong(query: String): Result<SongItem?> {
        queries.add(query)
        gate?.await()
        return answers[query] ?: Result.success(null)
    }
}

class FakeSignIn(
    initial: Boolean? = true,
) : YouTubeSignIn {
    private val state = MutableSharedFlow<Boolean>(replay = 1)
    override val signedIn: Flow<Boolean> = state

    init {
        initial?.let { state.tryEmit(it) }
    }

    fun set(value: Boolean) {
        state.tryEmit(value)
    }
}

class SaralViewModelTest {
    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeHub
    private lateinit var family: FakeStore
    private lateinit var cache: FakeStationsCache
    private lateinit var search: FakeSearch
    private lateinit var playback: FakePlayback
    private lateinit var signIn: FakeSignIn

    private val bollywood = Station("default-bollywood-classics", "Bollywood Classics", "seedBollyw01", 0)
    private val bhajan = Station("default-bhajans-devotional", "Bhajans & Devotional", "seedBhajan01", 2)
    private val mine = Station("s9", "Mummy's Songs", "seedMummy001", 1)

    private fun song(
        id: String,
        title: String,
    ) = SongItem(id = id, title = title, artists = emptyList(), thumbnail = "")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        hub = FakeHub()
        family = FakeStore().apply { session = FamilySession("device-1", "the-session-token") }
        cache = FakeStationsCache(listOf(bollywood, bhajan))
        search = FakeSearch()
        playback = FakePlayback()
        signIn = FakeSignIn()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newVm() = SaralViewModel(cache, StationsRefresher(hub, family, cache), search, signIn, scope)

    private fun SaralViewModel.ready(): SaralViewModel {
        bindPlayback(playback)
        return this
    }

    private fun SaralViewModel.tile(station: Station) = tiles.value.first { it.station.id == station.id }

    private fun stationSubject(
        vm: SaralViewModel,
        station: Station,
    ) = SaralSubject.Station(vm.tile(station))

    // --- tiles come from the cache ------------------------------------------

    @Test
    fun `the tiles are the cached stations, present before the hub has said anything`() {
        hub.stationsGate = CompletableDeferred()

        val vm = newVm()

        assertEquals(listOf(bollywood, bhajan), vm.tiles.value.map { it.station })
    }

    @Test
    fun `a hub that never answers cannot block the tiles or a tap`() {
        hub.stationsGate = CompletableDeferred()
        val vm = newVm().ready()

        vm.onTile(bhajan.id)

        assertEquals(listOf("radio(seedBhajan01)"), playback.calls.toList())
        assertEquals(listOf(bollywood, bhajan), vm.tiles.value.map { it.station })
    }

    @Test
    fun `on start it refreshes the cache with this phone's own session`() {
        hub.stations = listOf(mine)

        val vm = newVm()

        assertEquals(listOf("getStations(the-session-token)"), hub.callsTo("getStations"))
        assertEquals(listOf(mine), cache.snapshot())
        assertEquals(listOf(mine), vm.tiles.value.map { it.station })
    }

    @Test
    fun `a phone with no session does not call the hub and keeps what it has`() {
        family.session = null

        val vm = newVm()

        assertTrue(hub.callsTo("getStations").isEmpty())
        assertEquals(listOf(bollywood, bhajan), vm.tiles.value.map { it.station })
    }

    @Test
    fun `an unreachable hub, a revoked phone and an empty list all keep the cached tiles`() {
        for (failure in listOf<Throwable>(OFFLINE, hubFailure(401))) {
            hub.failWith("getStations", failure)
            assertEquals(listOf(bollywood, bhajan), newVm().tiles.value.map { it.station })
        }
        hub.heal("getStations")
        hub.stations = emptyList()
        assertEquals(listOf(bollywood, bhajan), newVm().tiles.value.map { it.station })
    }

    @Test
    fun `a cache that cannot be written still leaves the tiles working`() {
        hub.stations = listOf(mine)
        cache.failWrites = IOException("disk full")

        val vm = newVm()

        assertEquals(listOf(bollywood, bhajan), vm.tiles.value.map { it.station })
    }

    @Test
    fun `the two starting stations carry their Hindi names and an edited one keeps the owner's words`() {
        cache = FakeStationsCache(listOf(bollywood, mine))

        val vm = newVm()

        assertEquals(SaralLabels.hindiLabelFor(bollywood), vm.tiles.value[0].hindiLabel)
        assertNull(vm.tiles.value[1].hindiLabel)
    }

    // --- tapping a tile -----------------------------------------------------

    @Test
    fun `a tile starts a radio seeded from that station's video, and says it is starting, not playing`() {
        val vm = newVm().ready()

        vm.onTile(bollywood.id)

        assertEquals(listOf("radio(seedBollyw01)"), playback.calls.toList())
        assertEquals(SaralStatus.Starting(stationSubject(vm, bollywood)), vm.status.value)
    }

    @Test
    fun `it says playing only once the player holds a track from that queue`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)

        playback.playingOurs("Kabhi Kabhie")

        assertEquals(SaralStatus.Playing(stationSubject(vm, bollywood)), vm.status.value)
        assertEquals(bollywood.id, vm.status.value.stationId())
    }

    @Test
    fun `a player still playing the old queue does not make the new tile look started`() {
        val vm = newVm().ready()
        playback.playingSomethingElse("Old song")

        vm.onTile(bhajan.id)

        assertEquals(SaralStatus.Starting(stationSubject(vm, bhajan)), vm.status.value)
    }

    @Test
    fun `pausing shows paused, and playing again shows playing`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        playback.playingOurs("Kabhi Kabhie")

        playback.playingOurs("Kabhi Kabhie", playing = false)
        assertEquals(SaralStatus.Paused(stationSubject(vm, bollywood)), vm.status.value)

        playback.playingOurs("Kabhi Kabhie", playing = true)
        assertEquals(SaralStatus.Playing(stationSubject(vm, bollywood)), vm.status.value)
    }

    @Test
    fun `a station radio moving on to the next track keeps the station's name and ring`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        playback.playingOurs("Song one")

        playback.playingOurs("Song two")

        assertEquals(SaralStatus.Playing(stationSubject(vm, bollywood)), vm.status.value)
        assertEquals(bollywood.id, vm.status.value.stationId())
    }

    @Test
    fun `tapping another tile moves the ring to it`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        playback.playingOurs("Song one")

        vm.onTile(bhajan.id)

        assertEquals(listOf("radio(seedBollyw01)", "radio(seedBhajan01)"), playback.calls.toList())
        assertEquals(bhajan.id, vm.status.value.stationId())
    }

    @Test
    fun `when something else starts playing the ring goes and the status names the real track`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        playback.playingOurs("Song one")

        playback.playingSomethingElse("Car's choice")

        assertNull(vm.status.value.stationId())
        assertEquals(SaralStatus.Playing(SaralSubject.Track("Car's choice")), vm.status.value)
    }

    @Test
    fun `the player reconnecting, such as after a rotation, does not lose the ring`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        playback.playingOurs("Song one")

        vm.bindPlayback(null)
        vm.bindPlayback(playback)

        assertEquals(SaralStatus.Playing(stationSubject(vm, bollywood)), vm.status.value)
    }

    @Test
    fun `a tap before the player is connected starts as soon as it connects`() {
        val vm = newVm()

        vm.onTile(bhajan.id)
        assertTrue(playback.calls.isEmpty())
        assertEquals(SaralStatus.Starting(stationSubject(vm, bhajan)), vm.status.value)

        vm.bindPlayback(playback)

        assertEquals(listOf("radio(seedBhajan01)"), playback.calls.toList())
    }

    @Test
    fun `only the last tap before the player connects plays`() {
        val vm = newVm()

        vm.onTile(bollywood.id)
        vm.onTile(bhajan.id)
        vm.bindPlayback(playback)

        assertEquals(listOf("radio(seedBhajan01)"), playback.calls.toList())
    }

    @Test
    fun `a tile that is not in the list does nothing`() {
        val vm = newVm().ready()

        vm.onTile("nobody")

        assertTrue(playback.calls.isEmpty())
        assertEquals(SaralStatus.Idle, vm.status.value)
    }

    @Test
    fun `a station with no video never starts anything`() {
        cache = FakeStationsCache(listOf(bollywood.copy(seedVideoId = "")))
        val vm = newVm().ready()

        vm.onTile(bollywood.id)

        assertTrue(playback.calls.isEmpty())
        assertEquals(SaralStatus.Idle, vm.status.value)
    }

    // --- failures are said out loud ----------------------------------------

    @Test
    fun `a tile that cannot start because the phone is offline says so, and drops the ring`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)

        playback.playerState.value = SaralPlayerState(problem = PlaybackProblem.OFFLINE)

        assertEquals(SaralStatus.Problem(PlaybackProblem.OFFLINE), vm.status.value)
        assertNull(vm.status.value.stationId())
    }

    @Test
    fun `a tile that cannot start for another reason says it could not play`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)

        playback.playerState.value = SaralPlayerState(problem = PlaybackProblem.COULD_NOT_PLAY)

        assertEquals(SaralStatus.Problem(PlaybackProblem.COULD_NOT_PLAY), vm.status.value)
    }

    @Test
    fun `a song that fails halfway says so even though it was playing`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        playback.playingOurs("Song one")

        playback.playerState.value = playback.playerState.value.copy(problem = PlaybackProblem.OFFLINE)

        assertEquals(SaralStatus.Problem(PlaybackProblem.OFFLINE), vm.status.value)
    }

    @Test
    fun `the message goes away when the player recovers`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        playback.playerState.value = SaralPlayerState(problem = PlaybackProblem.OFFLINE)

        playback.playingOurs("Song one")

        assertEquals(SaralStatus.Playing(stationSubject(vm, bollywood)), vm.status.value)
    }

    @Test
    fun `something already playing, with no tap, is reported as what it is`() {
        val vm = newVm().ready()

        playback.playingSomethingElse("Restored song", playing = false)

        assertEquals(SaralStatus.Paused(SaralSubject.Track("Restored song")), vm.status.value)
    }

    // --- transport ----------------------------------------------------------

    @Test
    fun `next and previous go to the player`() {
        val vm = newVm().ready()

        vm.onNext()
        vm.onPrevious()

        assertEquals(listOf("next", "previous"), playback.calls.toList())
    }

    @Test
    fun `play and pause toggles what is already queued`() {
        playback.queued = true
        val vm = newVm().ready()

        vm.onPlayPause()

        assertEquals(listOf("toggle"), playback.calls.toList())
    }

    @Test
    fun `play with nothing queued starts the first station instead of doing nothing`() {
        val vm = newVm().ready()

        vm.onPlayPause()

        assertEquals(listOf("radio(seedBollyw01)"), playback.calls.toList())
        assertEquals(bollywood.id, vm.status.value.stationId())
    }

    @Test
    fun `play with nothing queued and no stations does nothing`() {
        cache = FakeStationsCache()
        val vm = newVm().ready()

        vm.onPlayPause()

        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `isPlaying follows the player and is false while there is none`() {
        val vm = newVm()
        assertFalse(vm.isPlaying.value)

        vm.bindPlayback(playback)
        playback.playingOurs("Song one", playing = true)
        assertTrue(vm.isPlaying.value)

        playback.playingOurs("Song one", playing = false)
        assertFalse(vm.isPlaying.value)

        playback.playingOurs("Song one", playing = true)
        vm.bindPlayback(null)
        assertFalse(vm.isPlaying.value)
    }

    // --- voice search -------------------------------------------------------

    private fun SaralViewModel.heard(vararg guesses: String) = onVoiceOutcome(VoiceOutcome.Heard(guesses.toList()))

    @Test
    fun `tapping the microphone opens the listening state and asks the screen to launch the recogniser`() {
        val vm = newVm().ready()

        assertTrue(vm.onMicTapped())

        assertEquals(VoicePhase.LISTENING, vm.voice.value)
    }

    @Test
    fun `a second tap while the recogniser is opening does not launch it twice`() {
        val vm = newVm().ready()
        vm.onMicTapped()

        assertFalse(vm.onMicTapped())
        assertEquals(VoicePhase.LISTENING, vm.voice.value)
    }

    @Test
    fun `the listening screen cannot be dismissed while the recogniser is in flight`() {
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.onOverlayDismissed()

        assertEquals(VoicePhase.LISTENING, vm.voice.value)
    }

    @Test
    fun `the recogniser's answer is accepted after the overlay was tapped during launch`() {
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm().ready()
        vm.onMicTapped()
        vm.onOverlayDismissed()

        vm.heard("लग जा गले")

        assertEquals(listOf("song(vid-lag)"), playback.calls.toList())
    }

    @Test
    fun `an answer nobody asked for is ignored`() {
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm().ready()

        vm.heard("लग जा गले")

        assertTrue(search.queries.isEmpty())
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `a second answer for the same launch is ignored`() {
        search.answers["a"] = Result.success(song("vid-a", "A"))
        val vm = newVm().ready()
        vm.onMicTapped()
        vm.heard("a")

        vm.heard("a")

        assertEquals(listOf("a"), search.queries.toList())
    }

    @Test
    fun `while the search runs the overlay says searching, and tapping it cancels the search`() {
        val inFlight = CompletableDeferred<Unit>()
        search.gate = inFlight
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.heard("लग जा गले")
        assertEquals(VoicePhase.SEARCHING, vm.voice.value)

        vm.onOverlayDismissed()
        inFlight.complete(Unit)

        assertEquals(VoicePhase.IDLE, vm.voice.value)
        assertTrue("a cancelled search plays nothing", playback.calls.isEmpty())
    }

    @Test
    fun `a spoken song name is searched and the first song plays like a tapped search result`() {
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.heard("लग जा गले")

        assertEquals(listOf("लग जा गले"), search.queries.toList())
        assertEquals(listOf("song(vid-lag)"), playback.calls.toList())
        assertEquals(VoicePhase.IDLE, vm.voice.value)
        assertEquals(SaralStatus.Starting(SaralSubject.Track("Lag Ja Gale")), vm.status.value)
    }

    @Test
    fun `a voice song follows the real track, including pause and the next song`() {
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm().ready()
        vm.onMicTapped()
        vm.heard("लग जा गले")

        playback.playingOurs("Lag Ja Gale")
        assertEquals(SaralStatus.Playing(SaralSubject.Track("Lag Ja Gale")), vm.status.value)

        playback.playingOurs("Lag Ja Gale", playing = false)
        assertEquals(SaralStatus.Paused(SaralSubject.Track("Lag Ja Gale")), vm.status.value)

        playback.playingOurs("Next one in the radio")
        assertEquals(SaralStatus.Playing(SaralSubject.Track("Next one in the radio")), vm.status.value)
        assertNull(vm.status.value.stationId())
    }

    @Test
    fun `a voice song drops the station ring that was on`() {
        search.answers["x"] = Result.success(song("vid-x", "X"))
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        playback.playingOurs("Song one")
        vm.onMicTapped()

        vm.heard("x")

        assertNull(vm.status.value.stationId())
    }

    @Test
    fun `if the first thing heard finds nothing, the next guess is tried`() {
        search.answers["lug ja gale"] = Result.success(null)
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.heard("lug ja gale", "  ", "लग जा गले")

        assertEquals(listOf("lug ja gale", "लग जा गले"), search.queries.toList())
        assertEquals(listOf("song(vid-lag)"), playback.calls.toList())
    }

    @Test
    fun `nothing found says so and plays nothing`() {
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.heard("xyzzy")

        assertTrue(playback.calls.isEmpty())
        assertEquals(SaralStatus.Voice(VoiceNotice.NOT_FOUND), vm.status.value)
        assertEquals(VoicePhase.IDLE, vm.voice.value)
    }

    @Test
    fun `a search that fails because the phone is offline says offline, not not-found`() {
        search.answers["लग जा गले"] = Result.failure(UnknownHostException("music.youtube.com"))
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.heard("लग जा गले", "लग जा गले ")

        assertEquals(SaralStatus.Voice(VoiceNotice.OFFLINE), vm.status.value)
        assertEquals("one failure ends the attempt", 1, search.queries.size)
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `a search that fails for another reason says it did not work, not not-found`() {
        search.answers["लग जा गले"] = Result.failure(IllegalStateException("bad response"))
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.heard("लग जा गले")

        assertEquals(SaralStatus.Voice(VoiceNotice.FAILED), vm.status.value)
    }

    @Test
    fun `speech that came back empty says it was not understood, without searching`() {
        val vm = newVm().ready()
        vm.onMicTapped()
        vm.onVoiceOutcome(VoiceOutcome.Heard(null))
        assertEquals(SaralStatus.Voice(VoiceNotice.NOT_HEARD), vm.status.value)

        vm.onMicTapped()
        vm.heard("", "   ")
        assertEquals(SaralStatus.Voice(VoiceNotice.NOT_HEARD), vm.status.value)
        assertTrue(search.queries.isEmpty())
    }

    @Test
    fun `each way the recogniser can end shows the matching message`() {
        val expected =
            mapOf(
                VoiceOutcome.NotHeard to VoiceNotice.NOT_HEARD,
                VoiceOutcome.Offline to VoiceNotice.OFFLINE,
                VoiceOutcome.Failed to VoiceNotice.FAILED,
            )
        for ((outcome, notice) in expected) {
            val vm = newVm().ready()
            vm.onMicTapped()

            vm.onVoiceOutcome(outcome)

            assertEquals("$outcome", SaralStatus.Voice(notice), vm.status.value)
            assertEquals(VoicePhase.IDLE, vm.voice.value)
        }
    }

    @Test
    fun `the user cancelling the recogniser closes the overlay and says nothing`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        val before = vm.status.value
        vm.onMicTapped()

        vm.onVoiceOutcome(VoiceOutcome.Cancelled)

        assertEquals(VoicePhase.IDLE, vm.voice.value)
        assertEquals(before, vm.status.value)
    }

    @Test
    fun `a phone with no speech service says so`() {
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.onVoiceUnavailable()

        assertEquals(SaralStatus.Voice(VoiceNotice.UNAVAILABLE), vm.status.value)
        assertEquals(VoicePhase.IDLE, vm.voice.value)
    }

    @Test
    fun `a song found before the player connects plays when it does`() {
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm()
        vm.onMicTapped()
        vm.heard("लग जा गले")
        assertTrue(playback.calls.isEmpty())

        vm.bindPlayback(playback)

        assertEquals(listOf("song(vid-lag)"), playback.calls.toList())
    }

    @Test
    fun `a new tap clears an old voice message`() {
        val vm = newVm().ready()
        vm.onMicTapped()
        vm.heard("xyzzy")
        assertEquals(SaralStatus.Voice(VoiceNotice.NOT_FOUND), vm.status.value)

        vm.onTile(bhajan.id)

        assertTrue(vm.status.value is SaralStatus.Starting)
    }

    // --- sign-in tile -------------------------------------------------------

    @Test
    fun `a phone signed in to YouTube has no sign-in tile, not even before storage has been read`() {
        signIn = FakeSignIn(initial = null)

        val vm = newVm()
        assertTrue("no flash of the tile before the answer is known", vm.signedIn.value)

        signIn.set(true)
        assertTrue(vm.signedIn.value)
    }

    @Test
    fun `a signed-out phone shows the sign-in tile`() {
        signIn = FakeSignIn(initial = false)

        val vm = newVm()

        assertFalse(vm.signedIn.value)
    }

    @Test
    fun `the tile opens the sign-in screen and closing it returns to the tiles`() {
        signIn = FakeSignIn(initial = false)
        val vm = newVm().ready()
        assertFalse(vm.signingIn.value)

        vm.onSignInTapped()
        assertTrue(vm.signingIn.value)

        vm.onSignInClosed()
        assertFalse(vm.signingIn.value)
    }

    @Test
    fun `signing in elsewhere hides the tile and closes the sign-in screen`() {
        signIn = FakeSignIn(initial = false)
        val vm = newVm()
        vm.onSignInTapped()

        signIn.set(true)

        assertTrue(vm.signedIn.value)
        assertFalse(vm.signingIn.value)
    }

    @Test
    fun `the sign-in screen cannot be opened on a phone that is signed in`() {
        val vm = newVm()

        vm.onSignInTapped()

        assertFalse(vm.signingIn.value)
    }
}

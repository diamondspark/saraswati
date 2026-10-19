/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the grandparents' screen, without the screen. The
// tiles come from the local cache and never wait for the hub; a tile starts a
// YouTube radio on its station's seed video; a spoken song name goes through
// the same search-then-play-with-radio path a tapped search result takes.
// Unconfined dispatching makes each call finish before it returns, because the
// fakes never suspend (except the gated hub in the "never blocks" test).
package com.metrolist.music.saraswati.saral

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FakeStore
import com.metrolist.music.saraswati.family.FamilySession
import com.metrolist.music.saraswati.family.OFFLINE
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.hubFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class FakePlayback : SaralPlayback {
    val calls = CopyOnWriteArrayList<String>()
    val playing = MutableStateFlow(false)
    var queued = false

    override val isPlaying: StateFlow<Boolean> = playing

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
}

class FakeSearch : SaralSearch {
    val queries = CopyOnWriteArrayList<String>()
    val answers = HashMap<String, Result<SongItem?>>()

    override suspend fun firstSong(query: String): Result<SongItem?> {
        queries.add(query)
        return answers[query] ?: Result.success(null)
    }
}

class SaralViewModelTest {
    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeHub
    private lateinit var family: FakeStore
    private lateinit var cache: FakeStationsCache
    private lateinit var search: FakeSearch
    private lateinit var playback: FakePlayback

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
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newVm() = SaralViewModel(cache, hub, family, search, scope)

    private fun SaralViewModel.ready(): SaralViewModel {
        bindPlayback(playback)
        return this
    }

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
    fun `refresh can be asked for again, for example when the app comes back`() {
        val vm = newVm()
        hub.stations = listOf(mine)

        vm.refresh()

        assertEquals(2, hub.callsTo("getStations").size)
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
    fun `an unreachable hub keeps the cached tiles`() {
        hub.failWith("getStations", OFFLINE)

        val vm = newVm()

        assertEquals(listOf(bollywood, bhajan), vm.tiles.value.map { it.station })
    }

    @Test
    fun `a revoked phone keeps the cached tiles`() {
        hub.failWith("getStations", hubFailure(401))

        val vm = newVm()

        assertEquals(listOf(bollywood, bhajan), vm.tiles.value.map { it.station })
    }

    @Test
    fun `a hub with no stations set does not blank the tiles`() {
        hub.stations = emptyList()

        val vm = newVm()

        assertEquals(listOf(bollywood, bhajan), vm.tiles.value.map { it.station })
    }

    @Test
    fun `a cache that cannot be written still leaves the tiles working`() {
        hub.stations = listOf(mine)
        cache.failWrites = java.io.IOException("disk full")

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
    fun `a tile starts a radio seeded from that station's video`() {
        val vm = newVm().ready()

        vm.onTile(bollywood.id)

        assertEquals(listOf("radio(seedBollyw01)"), playback.calls.toList())
        assertEquals(SaralStatus.StationPlaying(vm.tiles.value[0]), vm.status.value)
    }

    @Test
    fun `tapping another tile switches the radio and the highlighted tile`() {
        val vm = newVm().ready()

        vm.onTile(bollywood.id)
        vm.onTile(bhajan.id)

        assertEquals(listOf("radio(seedBollyw01)", "radio(seedBhajan01)"), playback.calls.toList())
        assertEquals(bhajan.id, (vm.status.value as SaralStatus.StationPlaying).tile.station.id)
    }

    @Test
    fun `a tap before the player is connected starts as soon as it connects`() {
        val vm = newVm()

        vm.onTile(bhajan.id)
        assertTrue(playback.calls.isEmpty())
        assertTrue(vm.status.value is SaralStatus.StationPlaying)

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
        assertEquals(bollywood.id, (vm.status.value as SaralStatus.StationPlaying).tile.station.id)
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
        playback.playing.value = true
        assertTrue(vm.isPlaying.value)

        playback.playing.value = false
        assertFalse(vm.isPlaying.value)

        playback.playing.value = true
        vm.bindPlayback(null)
        assertFalse(vm.isPlaying.value)
    }

    // --- voice search -------------------------------------------------------

    @Test
    fun `tapping the microphone opens the listening state`() {
        val vm = newVm().ready()

        vm.onMicTapped()

        assertTrue(vm.voiceActive.value)
    }

    @Test
    fun `a spoken song name is searched and the first song plays like a tapped search result`() {
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.onVoiceResult(listOf("लग जा गले"))

        assertEquals(listOf("लग जा गले"), search.queries.toList())
        assertEquals(listOf("song(vid-lag)"), playback.calls.toList())
        assertEquals(SaralStatus.SongPlaying("Lag Ja Gale"), vm.status.value)
        assertFalse(vm.voiceActive.value)
    }

    @Test
    fun `if the first thing heard finds nothing, the next guess is tried`() {
        search.answers["lug ja gale"] = Result.success(null)
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.onVoiceResult(listOf("lug ja gale", "  ", "लग जा गले"))

        assertEquals(listOf("lug ja gale", "लग जा गले"), search.queries.toList())
        assertEquals(listOf("song(vid-lag)"), playback.calls.toList())
    }

    @Test
    fun `nothing found says so and plays nothing`() {
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.onVoiceResult(listOf("xyzzy"))

        assertTrue(playback.calls.isEmpty())
        assertEquals(SaralStatus.VoiceNotFound, vm.status.value)
        assertFalse(vm.voiceActive.value)
    }

    @Test
    fun `a search that fails, such as with no signal, says nothing was found`() {
        search.answers["लग जा गले"] = Result.failure(OFFLINE)
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.onVoiceResult(listOf("लग जा गले"))

        assertEquals(SaralStatus.VoiceNotFound, vm.status.value)
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `speech that came back empty says nothing was found without searching`() {
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.onVoiceResult(null)
        assertEquals(SaralStatus.VoiceNotFound, vm.status.value)

        vm.onMicTapped()
        vm.onVoiceResult(listOf("", "   "))
        assertEquals(SaralStatus.VoiceNotFound, vm.status.value)
        assertTrue(search.queries.isEmpty())
    }

    @Test
    fun `closing the listening screen leaves what was playing alone`() {
        val vm = newVm().ready()
        vm.onTile(bollywood.id)
        val before = vm.status.value
        vm.onMicTapped()

        vm.onVoiceCancelled()

        assertFalse(vm.voiceActive.value)
        assertEquals(before, vm.status.value)
    }

    @Test
    fun `an answer that arrives after the listening screen was closed is ignored`() {
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm().ready()
        vm.onMicTapped()
        vm.onVoiceCancelled()

        vm.onVoiceResult(listOf("लग जा गले"))

        assertTrue(playback.calls.isEmpty())
        assertTrue(search.queries.isEmpty())
    }

    @Test
    fun `a phone with no speech service says so`() {
        val vm = newVm().ready()
        vm.onMicTapped()

        vm.onVoiceUnavailable()

        assertEquals(SaralStatus.VoiceUnavailable, vm.status.value)
        assertFalse(vm.voiceActive.value)
    }

    @Test
    fun `a song found before the player connects plays when it does`() {
        search.answers["लग जा गले"] = Result.success(song("vid-lag", "Lag Ja Gale"))
        val vm = newVm()
        vm.onMicTapped()
        vm.onVoiceResult(listOf("लग जा गले"))
        assertTrue(playback.calls.isEmpty())

        vm.bindPlayback(playback)

        assertEquals(listOf("song(vid-lag)"), playback.calls.toList())
    }

    @Test
    fun `a new tap clears an old voice message`() {
        val vm = newVm().ready()
        vm.onMicTapped()
        vm.onVoiceResult(listOf("xyzzy"))
        assertEquals(SaralStatus.VoiceNotFound, vm.status.value)

        vm.onTile(bhajan.id)

        assertTrue(vm.status.value is SaralStatus.StationPlaying)
    }
}

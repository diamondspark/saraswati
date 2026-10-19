/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 10 — the festival ribbon on Saral, without the screen. The
// ribbon's button starts a radio the way a station tile does (a seed video and
// its mix), and says what is happening in the status line in Hindi: starting,
// playing, and when it cannot (no signal, nothing found, the player failing)
// the same large messages a tile shows. It never reaches another screen, and
// a tile or a spoken song that comes after it wins.
package com.metrolist.music.saraswati.saral

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.saraswati.culture.Festival
import com.metrolist.music.saraswati.culture.FestivalCalendar
import com.metrolist.music.saraswati.culture.FestivalClock
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FakeStore
import com.metrolist.music.saraswati.family.FamilySession
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsRefresher
import com.metrolist.music.saraswati.family.noThrottle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SaralFestivalTest {
    private lateinit var scope: CoroutineScope
    private lateinit var hub: FakeHub
    private lateinit var family: FakeStore
    private lateinit var cache: FakeStationsCache
    private lateinit var search: FakeSearch
    private lateinit var playback: FakePlayback
    private lateinit var signIn: FakeSignIn

    private val bollywood = Station("default-bollywood-classics", "Bollywood Classics", "seedBollyw01", 0)
    private val diwali: Festival = FestivalCalendar.all.first { it.name == "Diwali" && it.startDate.year == 2026 }

    private fun onDay(date: String) = FestivalClock(Clock.fixed(Instant.parse("${date}T05:00:00Z"), ZoneOffset.UTC))

    private fun song(
        id: String,
        title: String = "Song $id",
    ) = SongItem(id = id, title = title, artists = emptyList(), thumbnail = "")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        hub = FakeHub()
        family = FakeStore().apply { session = FamilySession("device-1", "the-session-token") }
        cache = FakeStationsCache(listOf(bollywood))
        search = FakeSearch()
        playback = FakePlayback()
        signIn = FakeSignIn()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newVm(clock: FestivalClock = onDay("2026-11-08")) =
        SaralViewModel(cache, StationsRefresher(hub, family, cache, noThrottle()), search, signIn, scope, clock)

    private fun SaralViewModel.ready(): SaralViewModel {
        bindPlayback(playback)
        return this
    }

    private fun festivalSubject() = SaralSubject.Festival(diwali)

    // --- whether the ribbon shows ------------------------------------------------

    @Test
    fun `on a festival day the view model offers that festival`() {
        assertEquals(diwali, newVm(onDay("2026-11-08")).festival.value)
    }

    @Test
    fun `on an ordinary day there is no festival`() {
        assertNull(newVm(onDay("2026-12-25")).festival.value)
    }

    @Test
    fun `the festival is the last day of the window too`() {
        assertEquals("Diwali", newVm(onDay("2026-11-11")).festival.value?.name)
        assertNull(newVm(onDay("2026-11-12")).festival.value)
    }

    // --- tapping it -----------------------------------------------------------------

    @Test
    fun `the ribbon searches the festival's radio query and starts a radio on the first song`() {
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()

        vm.onFestivalTapped()

        assertEquals(listOf("Diwali songs"), search.queries.toList())
        assertEquals(listOf("radio(seedDiwali01)"), playback.calls.toList())
    }

    @Test
    fun `it says the festival's music is starting, then playing once the player holds a track from it`() {
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()

        vm.onFestivalTapped()
        assertEquals(SaralStatus.Starting(festivalSubject()), vm.status.value)

        playback.playingOurs("Aayi Diwali")
        assertEquals(SaralStatus.Playing(festivalSubject()), vm.status.value)

        playback.playingOurs("Aayi Diwali", playing = false)
        assertEquals(SaralStatus.Paused(festivalSubject()), vm.status.value)
    }

    @Test
    fun `a festival radio rings no station tile`() {
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()

        vm.onFestivalTapped()
        playback.playingOurs("Aayi Diwali")

        assertNull(vm.status.value.stationId())
    }

    @Test
    fun `while the search is still running nothing has started and the status says starting`() {
        search.gate = CompletableDeferred()
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()

        vm.onFestivalTapped()

        assertTrue(playback.calls.isEmpty())
        assertEquals(SaralStatus.Starting(festivalSubject()), vm.status.value)

        search.gate!!.complete(Unit)

        assertEquals(listOf("radio(seedDiwali01)"), playback.calls.toList())
    }

    @Test
    fun `a tap before the music service has connected starts the radio the moment it does`() {
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm()

        vm.onFestivalTapped()
        assertTrue(playback.calls.isEmpty())

        vm.bindPlayback(playback)

        assertEquals(listOf("radio(seedDiwali01)"), playback.calls.toList())
    }

    @Test
    fun `with no festival today a tap does nothing`() {
        val vm = newVm(onDay("2026-12-25")).ready()

        vm.onFestivalTapped()

        assertTrue(search.queries.isEmpty())
        assertTrue(playback.calls.isEmpty())
        assertEquals(SaralStatus.Idle, vm.status.value)
    }

    // --- when it cannot --------------------------------------------------------------

    @Test
    fun `a search that finds no song says the music could not play, and starts nothing`() {
        search.answers["Diwali songs"] = Result.success(null)
        val vm = newVm().ready()

        vm.onFestivalTapped()

        assertEquals(SaralStatus.Problem(PlaybackProblem.COULD_NOT_PLAY), vm.status.value)
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `a search with no signal says there is no internet`() {
        search.answers["Diwali songs"] = Result.failure(UnknownHostException("music.youtube.com"))
        val vm = newVm().ready()

        vm.onFestivalTapped()

        assertEquals(SaralStatus.Problem(PlaybackProblem.OFFLINE), vm.status.value)
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `any other failed search says the music could not play`() {
        search.answers["Diwali songs"] = Result.failure(IllegalStateException("bad response"))
        val vm = newVm().ready()

        vm.onFestivalTapped()

        assertEquals(SaralStatus.Problem(PlaybackProblem.COULD_NOT_PLAY), vm.status.value)
    }

    @Test
    fun `the player failing after the radio started shows the player's own message`() {
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()
        vm.onFestivalTapped()

        playback.playerState.value = SaralPlayerState(problem = PlaybackProblem.OFFLINE)

        assertEquals(SaralStatus.Problem(PlaybackProblem.OFFLINE), vm.status.value)
    }

    @Test
    fun `a failed festival message goes when the person taps a tile, play, next, previous or the ribbon again`() {
        search.answers["Diwali songs"] = Result.success(null)
        val vm = newVm().ready()

        vm.onFestivalTapped()
        assertEquals(SaralStatus.Problem(PlaybackProblem.COULD_NOT_PLAY), vm.status.value)
        vm.onTile(bollywood.id)
        assertEquals(bollywood.id, vm.status.value.stationId())

        vm.onFestivalTapped()
        assertEquals(SaralStatus.Problem(PlaybackProblem.COULD_NOT_PLAY), vm.status.value)
        vm.onNext()
        assertTrue(vm.status.value !is SaralStatus.Problem)

        vm.onFestivalTapped()
        vm.onPrevious()
        assertTrue(vm.status.value !is SaralStatus.Problem)

        vm.onFestivalTapped()
        vm.onPlayPause()
        assertTrue(vm.status.value !is SaralStatus.Problem)

        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        vm.onFestivalTapped()
        assertEquals(SaralStatus.Starting(festivalSubject()), vm.status.value)
    }

    // --- SARASWATI: fix wave B2 (M1) — the search must not borrow the status of what is already playing ---

    private fun startBollywoodPlaying(vm: SaralViewModel) {
        vm.onTile(bollywood.id)
        playback.playingOurs("Kabhi Kabhie")
        assertEquals(SaralStatus.Playing(SaralSubject.Station(vm.tile(bollywood))), vm.status.value)
    }

    private fun SaralViewModel.tile(station: Station) = tiles.value.first { it.station.id == station.id }

    @Test
    fun `while the festival search runs, a station that is playing is not reported as the festival`() {
        search.gate = CompletableDeferred()
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()
        startBollywoodPlaying(vm)

        vm.onFestivalTapped()

        assertEquals("looking for the songs is starting, not playing", SaralStatus.Starting(festivalSubject()), vm.status.value)
        assertNull("and the station that is still playing is not ringed as if it were the festival", vm.status.value.stationId())
        assertEquals("nothing was asked of the player yet", listOf("radio(seedBollyw01)"), playback.calls.toList())
    }

    @Test
    fun `the festival radio starts when the song is found, and only then does it become the request`() {
        val gate = CompletableDeferred<Unit>()
        search.gate = gate
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()
        startBollywoodPlaying(vm)
        vm.onFestivalTapped()

        gate.complete(Unit)

        assertEquals(listOf("radio(seedBollyw01)", "radio(seedDiwali01)"), playback.calls.toList())
        // The player has moved to the festival's queue: its track is ours, the station's is not.
        playback.playingOurs("Aayi Diwali")
        assertEquals(SaralStatus.Playing(festivalSubject()), vm.status.value)
    }

    @Test
    fun `a festival search that finds nothing says so and does not claim the station that was playing`() {
        search.answers["Diwali songs"] = Result.success(null)
        val vm = newVm().ready()
        startBollywoodPlaying(vm)

        vm.onFestivalTapped()

        assertEquals(SaralStatus.Problem(PlaybackProblem.COULD_NOT_PLAY), vm.status.value)
        assertEquals(listOf("radio(seedBollyw01)"), playback.calls.toList())
    }

    // SARASWATI: fix wave B3 — a failed search leaves the festival out of it altogether: no request was begun, so the
    // next thing the person does reports the station that is really playing. (If the request began at the tap, this
    // would say the festival was playing.)
    @Test
    fun `after a festival search finds nothing, play and pause names the station that is playing`() {
        search.answers["Diwali songs"] = Result.success(null)
        val vm = newVm().ready()
        startBollywoodPlaying(vm)
        vm.onFestivalTapped()
        assertEquals(SaralStatus.Problem(PlaybackProblem.COULD_NOT_PLAY), vm.status.value)

        vm.onPlayPause()

        assertEquals(listOf("radio(seedBollyw01)", "toggle"), playback.calls.toList())
        assertEquals(SaralStatus.Playing(SaralSubject.Station(vm.tile(bollywood))), vm.status.value)
        assertEquals(bollywood.id, vm.status.value.stationId())
    }

    @Test
    fun `after a festival search with no signal, play and pause names the station that is playing`() {
        search.answers["Diwali songs"] = Result.failure(UnknownHostException("music.youtube.com"))
        val vm = newVm().ready()
        startBollywoodPlaying(vm)
        vm.onFestivalTapped()
        assertEquals(SaralStatus.Problem(PlaybackProblem.OFFLINE), vm.status.value)

        vm.onPlayPause()

        assertEquals(SaralStatus.Playing(SaralSubject.Station(vm.tile(bollywood))), vm.status.value)
    }

    @Test
    fun `after a failed festival search the station that was playing keeps its ring`() {
        search.answers["Diwali songs"] = Result.success(null)
        val vm = newVm().ready()
        startBollywoodPlaying(vm)
        vm.onFestivalTapped()

        vm.onNext()

        assertEquals(bollywood.id, vm.status.value.stationId())
    }

    @Test
    fun `a festival search with no signal says so while a station plays`() {
        search.answers["Diwali songs"] = Result.failure(UnknownHostException("music.youtube.com"))
        val vm = newVm().ready()
        startBollywoodPlaying(vm)

        vm.onFestivalTapped()

        assertEquals(SaralStatus.Problem(PlaybackProblem.OFFLINE), vm.status.value)
    }

    @Test
    fun `a tile tapped during the search ends the searching status`() {
        search.gate = CompletableDeferred()
        val vm = newVm().ready()
        vm.onFestivalTapped()
        assertEquals(SaralStatus.Starting(festivalSubject()), vm.status.value)

        vm.onTile(bollywood.id)

        assertEquals(bollywood.id, vm.status.value.stationId())
        assertTrue(vm.status.value is SaralStatus.Starting)
        assertEquals(SaralSubject.Station(vm.tile(bollywood)), (vm.status.value as SaralStatus.Starting).subject)
    }

    @Test
    fun `the rules put searching ahead of a request the player is already running, and behind a failure`() {
        val running = SaralPlayerState(mediaId = "m1", title = "Kabhi Kabhie", playing = true, ours = true)
        val tile = SaralTile(bollywood, null)

        assertEquals(
            SaralStatus.Starting(festivalSubject()),
            SaralStatusRules.derive(SaralRequest.Station(tile), running, notice = null, problem = null, searching = diwali),
        )
        assertEquals(
            SaralStatus.Problem(PlaybackProblem.OFFLINE),
            SaralStatusRules.derive(SaralRequest.Station(tile), running, notice = null, problem = PlaybackProblem.OFFLINE, searching = diwali),
        )
        assertEquals(
            SaralStatus.Voice(VoiceNotice.NOT_HEARD),
            SaralStatusRules.derive(null, running, notice = VoiceNotice.NOT_HEARD, problem = null, searching = diwali),
        )
        assertEquals(
            SaralStatus.Playing(SaralSubject.Station(tile)),
            SaralStatusRules.derive(SaralRequest.Station(tile), running, notice = null, problem = null, searching = null),
        )
    }

    // --- against the other things Saral does ---------------------------------------------

    @Test
    fun `a tile tapped while the festival search runs wins, and the late answer starts nothing`() {
        search.gate = CompletableDeferred()
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()
        vm.onFestivalTapped()

        vm.onTile(bollywood.id)
        search.gate!!.complete(Unit)

        assertEquals(listOf("radio(seedBollyw01)"), playback.calls.toList())
        assertEquals(bollywood.id, vm.status.value.stationId())
    }

    @Test
    fun `tapping the ribbon twice starts one radio`() {
        search.gate = CompletableDeferred()
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()

        vm.onFestivalTapped()
        vm.onFestivalTapped()
        search.gate!!.complete(Unit)

        assertEquals(listOf("radio(seedDiwali01)"), playback.calls.toList())
    }

    @Test
    fun `a spoken song found while the festival search runs wins over it`() {
        val festivalGate = CompletableDeferred<Unit>()
        search.gate = festivalGate
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        search.answers["Lag Ja Gale"] = Result.success(song("lagjagale01", "Lag Ja Gale"))
        val vm = newVm().ready()
        vm.onFestivalTapped()
        search.gate = null

        vm.onMicTapped()
        vm.onVoiceOutcome(VoiceOutcome.Heard(listOf("Lag Ja Gale")))
        festivalGate.complete(Unit)

        assertEquals(listOf("song(lagjagale01)"), playback.calls.toList())
    }

    @Test
    fun `something else taking over the player drops the festival and names the real track`() {
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()
        vm.onFestivalTapped()
        playback.playingOurs("Aayi Diwali")

        playback.playingSomethingElse("Car's choice")

        assertEquals(SaralStatus.Playing(SaralSubject.Track("Car's choice")), vm.status.value)
    }

    @Test
    fun `a voice notice from before is cleared by the ribbon`() {
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()
        vm.onMicTapped()
        vm.onVoiceOutcome(VoiceOutcome.NotHeard)
        assertEquals(SaralStatus.Voice(VoiceNotice.NOT_HEARD), vm.status.value)

        vm.onFestivalTapped()

        assertEquals(SaralStatus.Starting(festivalSubject()), vm.status.value)
    }

    @Test
    fun `play with nothing queued still starts a station, not the festival`() {
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))
        val vm = newVm().ready()

        vm.onPlayPause()

        assertEquals(listOf("radio(seedBollyw01)"), playback.calls.toList())
        assertTrue(search.queries.isEmpty())
    }

    // --- its Hindi words -----------------------------------------------------------------

    @Test
    fun `every festival in the table has its own Hindi name for the status line`() {
        val generic = SaralFestivalNames.hindiNameFor(Festival("Unknown", diwali.startDate, diwali.endDate, "", "", ""))

        for (f in FestivalCalendar.all) {
            assertTrue("${f.name} needs its own Hindi name", SaralFestivalNames.hindiNameFor(f) != generic)
        }
        assertEquals(
            "five festivals, five names",
            5,
            FestivalCalendar.all.map { SaralFestivalNames.hindiNameFor(it) }.toSet().size,
        )
    }
}

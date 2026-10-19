/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B2 — a rotation rebuilds the adapter between Saral and the
// player (PlayerConnectionPlayback) over the same running player. The watch on
// the queue Saral started used to live in the adapter, so the new one began empty:
// the track still playing was no longer "ours", the status fell back to "starting"
// and the station lost its ring. The watch now lives in QueueWatchHolder. The
// adapter itself needs the real player; these tests drive the two pieces it is
// made of (the holder and saralPlayerState) with the same view model, and the
// error debounce, on virtual time.
package com.metrolist.music.saraswati.saral

import androidx.media3.common.MediaItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FakeStore
import com.metrolist.music.saraswati.family.FamilySession
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsRefresher
import com.metrolist.music.saraswati.family.VirtualTimeDispatcher
import com.metrolist.music.saraswati.family.noThrottle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** A queue that yields [ids] when the player asks for its first page. */
private class TracksQueue(
    private val ids: List<String>,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    override suspend fun getInitialStatus() = Queue.Status(title = "Radio", items = ids.map { MediaItem.Builder().setMediaId(it).build() }, mediaItemIndex = 0)

    override fun hasNextPage() = false

    override suspend fun nextPage(): List<MediaItem> = emptyList()
}

/**
 * Stands in for PlayerConnectionPlayback: the same state pipeline over a shared [holder] and one player [snapshot],
 * and a "start" that loads a queue through the holder's watch the way the real one does. Building a second one is
 * what a rotation does.
 */
private class HolderBackedPlayback(
    private val holder: QueueWatchHolder,
    private val snapshot: MutableStateFlow<PlayerSnapshot>,
) : SaralPlayback {
    override val state: Flow<SaralPlayerState> = saralPlayerState(holder, snapshot)

    override fun hasQueue() = snapshot.value.mediaId != null

    override fun playRadio(seedVideoId: String) {
        val watch = holder.startNew()
        runBlocking { watch.wrap(TracksQueue(listOf("$seedVideoId-1", "$seedVideoId-2"))).getInitialStatus() }
    }

    override fun playSong(song: SongItem) = Unit

    override fun togglePlayPause() = Unit

    override fun skipNext() = Unit

    override fun skipPrevious() = Unit
}

class SaralRebindTest {
    private lateinit var scope: CoroutineScope
    private lateinit var holder: QueueWatchHolder
    private lateinit var snapshot: MutableStateFlow<PlayerSnapshot>
    private lateinit var vm: SaralViewModel

    private val bollywood = Station("default-bollywood-classics", "Bollywood Classics", "seedBollyw01", 0)
    private val bhajan = Station("default-bhajans-devotional", "Bhajans & Devotional", "seedBhajan01", 2)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        holder = QueueWatchHolder()
        snapshot = MutableStateFlow(PlayerSnapshot(null, null, false, null))
        val cache = FakeStationsCache(listOf(bollywood, bhajan))
        val family = FakeStore().apply { session = FamilySession("device-1", "the-session-token") }
        vm = SaralViewModel(cache, StationsRefresher(FakeHub(), family, cache, noThrottle()), FakeSearch(), FakeSignIn(), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun stationRinged() = vm.status.value.stationId()

    @Test
    fun `a station that is playing is still playing after the screen binds a new adapter`() {
        vm.bindPlayback(HolderBackedPlayback(holder, snapshot))
        vm.onTile(bollywood.id)
        snapshot.value = PlayerSnapshot("seedBollyw01-1", "Kabhi Kabhie", playing = true, problem = null)
        assertTrue(vm.status.value is SaralStatus.Playing)
        assertEquals(bollywood.id, stationRinged())

        // The activity is recreated: the old adapter is let go, a new one is built over the same player.
        vm.bindPlayback(null)
        vm.bindPlayback(HolderBackedPlayback(holder, snapshot))

        assertTrue("not 'starting' again: ${vm.status.value}", vm.status.value is SaralStatus.Playing)
        assertEquals("the ring stays on the tile", bollywood.id, stationRinged())
    }

    @Test
    fun `a paused station is still paused after the screen binds a new adapter`() {
        vm.bindPlayback(HolderBackedPlayback(holder, snapshot))
        vm.onTile(bhajan.id)
        snapshot.value = PlayerSnapshot("seedBhajan01-1", "Om Jai Jagdish", playing = false, problem = null)
        assertTrue(vm.status.value is SaralStatus.Paused)

        vm.bindPlayback(null)
        vm.bindPlayback(HolderBackedPlayback(holder, snapshot))

        assertTrue(vm.status.value is SaralStatus.Paused)
        assertEquals(bhajan.id, stationRinged())
    }

    @Test
    fun `the track keeps belonging to Saral when the adapter is rebuilt, and a stranger still does not`() {
        val first = HolderBackedPlayback(holder, snapshot)
        first.playRadio("seedA")
        snapshot.value = PlayerSnapshot("seedA-1", "A", playing = true, problem = null)
        val rebuilt = HolderBackedPlayback(holder, snapshot)

        assertTrue(runBlocking { rebuilt.state.first() }.ours)

        snapshot.value = PlayerSnapshot("someone-else", "B", playing = true, problem = null)
        assertFalse(runBlocking { rebuilt.state.first() }.ours)
    }

    @Test
    fun `starting something new gives it a fresh watch, and the old queue is no longer ours`() {
        val playback = HolderBackedPlayback(holder, snapshot)
        playback.playRadio("seedA")
        val first = holder.watch.value
        snapshot.value = PlayerSnapshot("seedA-1", "A", playing = true, problem = null)
        assertTrue(runBlocking { playback.state.first() }.ours)

        playback.playRadio("seedB")

        assertFalse("a new watch", first === holder.watch.value)
        assertFalse("seedA's tracks are not seedB's", runBlocking { playback.state.first() }.ours)
    }

    @Test
    fun `the process holder is one object for every adapter, and no adapter builds its own watch`() {
        assertSame(QueueWatchHolder.process, QueueWatchHolder.process)
        val source = sourceOf("src/main/kotlin/com/metrolist/music/saraswati/saral/SaralPlayback.kt")
        val adapter = source.substringAfter("internal class PlayerConnectionPlayback")

        assertTrue(adapter.contains("watches: QueueWatchHolder = QueueWatchHolder.process"))
        assertTrue(adapter.contains("watches.startNew()"))
        assertFalse("the adapter must not create a QueueWatch of its own", adapter.contains("QueueWatch()"))
    }

    private fun sourceOf(path: String): String =
        (listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")).readText()
}

class PlayerProblemDelayTest {
    private lateinit var dispatcher: VirtualTimeDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var upstream: MutableStateFlow<PlaybackProblem?>
    private val seen = mutableListOf<PlaybackProblem?>()

    @Before
    fun setUp() {
        dispatcher = VirtualTimeDispatcher()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        upstream = MutableStateFlow(null)
        scope.launch { upstream.lasting(PLAYER_PROBLEM_DELAY_MS).collect { seen.add(it) } }
        dispatcher.runCurrent()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the delay is between one and two seconds`() {
        assertTrue(PLAYER_PROBLEM_DELAY_MS in 1_500L..2_000L)
    }

    @Test
    fun `no problem is no problem, at once`() {
        assertEquals(listOf<PlaybackProblem?>(null), seen)
    }

    @Test
    fun `a problem that clears before the delay is never shown`() {
        upstream.value = PlaybackProblem.COULD_NOT_PLAY
        dispatcher.advanceBy(PLAYER_PROBLEM_DELAY_MS - 1)
        upstream.value = null
        dispatcher.advanceBy(10_000)

        assertEquals(listOf<PlaybackProblem?>(null), seen)
    }

    @Test
    fun `a problem that lasts is shown when the delay is up, not before`() {
        upstream.value = PlaybackProblem.OFFLINE
        dispatcher.advanceBy(PLAYER_PROBLEM_DELAY_MS - 1)
        assertEquals(listOf<PlaybackProblem?>(null), seen)

        dispatcher.advanceBy(1)

        assertEquals(listOf(null, PlaybackProblem.OFFLINE), seen)
    }

    @Test
    fun `a problem that goes away is gone at once`() {
        upstream.value = PlaybackProblem.COULD_NOT_PLAY
        dispatcher.advanceBy(PLAYER_PROBLEM_DELAY_MS)
        assertEquals(PlaybackProblem.COULD_NOT_PLAY, seen.last())

        upstream.value = null
        dispatcher.runCurrent()

        assertEquals(listOf(null, PlaybackProblem.COULD_NOT_PLAY, null), seen)
    }

    @Test
    fun `a flicker (error, recovery, error) waits the full delay from the last error`() {
        upstream.value = PlaybackProblem.COULD_NOT_PLAY
        dispatcher.advanceBy(1_000)
        upstream.value = null
        dispatcher.advanceBy(100)
        upstream.value = PlaybackProblem.COULD_NOT_PLAY
        dispatcher.advanceBy(PLAYER_PROBLEM_DELAY_MS - 1)
        assertEquals(listOf<PlaybackProblem?>(null), seen)

        dispatcher.advanceBy(1)

        assertEquals(listOf(null, PlaybackProblem.COULD_NOT_PLAY), seen)
    }

    @Test
    fun `one problem turning into another keeps the first until the second has lasted`() {
        upstream.value = PlaybackProblem.OFFLINE
        dispatcher.advanceBy(PLAYER_PROBLEM_DELAY_MS)
        upstream.value = PlaybackProblem.COULD_NOT_PLAY
        dispatcher.advanceBy(PLAYER_PROBLEM_DELAY_MS - 1)
        assertEquals(PlaybackProblem.OFFLINE, seen.last())

        dispatcher.advanceBy(1)

        assertEquals(PlaybackProblem.COULD_NOT_PLAY, seen.last())
    }

    @Test
    fun `a queue that could not load is not delayed, because it is not the player's error`() {
        val holder = QueueWatchHolder()
        val states = mutableListOf<SaralPlayerState>()
        val snapshot = MutableStateFlow(PlayerSnapshot(null, null, false, null))
        scope.launch { saralPlayerState(holder, snapshot).collect { states.add(it) } }
        dispatcher.runCurrent()

        holder.startNew().record(java.io.IOException("no signal"))
        dispatcher.runCurrent()

        assertEquals(PlaybackProblem.OFFLINE, states.last().problem)
        assertEquals(0, dispatcher.currentTime)
    }
}

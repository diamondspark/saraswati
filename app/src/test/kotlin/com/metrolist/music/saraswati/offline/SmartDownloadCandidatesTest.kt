package com.metrolist.music.saraswati.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.ParivaarMix
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
import com.metrolist.music.saraswati.family.parivaar.PlaylistEntry
import com.metrolist.music.saraswati.family.parivaar.RankedSong
import com.metrolist.music.saraswati.family.parivaar.TopSongsSource
import com.metrolist.music.saraswati.family.parivaar.YouTubePlaylistPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class SmartDownloadCandidatesTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var mixStore: ParivaarMixStore
    private lateinit var stationStore: StationOfflineStore

    private val now = Instant.parse("2026-10-19T20:00:00Z")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
        mixStore = ParivaarMixStore(dataStore, scope)
        stationStore = StationOfflineStore(dataStore, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun track(id: String) = MixTrack(id, "title $id", "artist", null, 200_000L)

    private fun meta(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 200)

    private class FakeStations(
        list: List<Station>,
    ) : StationsCache {
        override val stations: StateFlow<List<Station>> = MutableStateFlow(list)
        override val loaded: StateFlow<Boolean> = MutableStateFlow(true)

        override suspend fun replace(stations: List<Station>) = Unit
    }

    private class FakePort(
        val answer: (String) -> List<MixTrack>,
    ) : YouTubePlaylistPort {
        val radioCalls = mutableListOf<String>()

        override suspend fun radio(seedVideoId: String): Result<List<MixTrack>> {
            radioCalls += seedVideoId
            return Result.success(answer(seedVideoId))
        }

        override suspend fun createUnlisted(title: String): Result<String> = error("unused")

        override suspend fun entries(playlistId: String): Result<List<PlaylistEntry>> = error("unused")

        override suspend fun add(
            playlistId: String,
            videoId: String,
        ): Result<Unit> = error("unused")

        override suspend fun remove(
            playlistId: String,
            videoId: String,
            setVideoId: String,
        ): Result<Unit> = error("unused")

        override suspend fun delete(playlistId: String): Result<Unit> = error("unused")
    }

    private fun candidates(
        port: YouTubePlaylistPort = FakePort { emptyList() },
        stations: List<Station> = emptyList(),
        mixOn: Boolean = true,
        top: List<RankedSong> = emptyList(),
        liked: List<MediaMetadata> = emptyList(),
        pauses: MutableList<Long> = mutableListOf(),
        onFetchFailed: suspend (Instant) -> Unit = {},
    ) = SmartDownloadCandidates(
        mixStore = mixStore,
        stationsCache = FakeStations(stations),
        stationStore = stationStore,
        port = port,
        topSongs =
            object : TopSongsSource {
                override suspend fun top(
                    since: Instant,
                    limit: Int,
                ) = top
            },
        library =
            object : SmartLibrary {
                override suspend fun stored(id: String): MediaMetadata? = null

                override suspend fun liked(limit: Int) = liked.take(limit)
            },
        mixEnabled = { mixOn },
        onFetchFailed = onFetchFailed,
        pause = { pauses += it },
    )

    @Test
    fun `skips the mix when parivaar_mix is off`() =
        runBlocking {
            mixStore.save(ParivaarMix(1L, listOf(track("mix00000001"))))
            val liked = listOf(meta("liked000001"))

            assertEquals(listOf("liked000001"), candidates(mixOn = false, liked = liked).gather(now).map { it.id })
            assertEquals(listOf("mix00000001", "liked000001"), candidates(mixOn = true, liked = liked).gather(now).map { it.id })
        }

    @Test
    fun `refreshes a week-old station set and keeps 8 tracks`() =
        runBlocking {
            val station = Station("st1", "Old songs", "seed0000001", 0)
            val twoDaysAgo = now.minusSeconds(2 * 86_400).toEpochMilli()
            val eightDaysAgo = now.minusSeconds(8 * 86_400).toEpochMilli()
            stationStore.save(StationOfflineSet("st1", "seed0000001", listOf(track("old00000001")), twoDaysAgo))
            val port = FakePort { (1..20).map { i -> track("new%08d".format(i)) } }

            // A set two days old is kept as it is: no request to YouTube.
            assertEquals(listOf("old00000001"), candidates(port, listOf(station)).gather(now).map { it.id })
            assertTrue(port.radioCalls.isEmpty())

            stationStore.save(StationOfflineSet("st1", "seed0000001", listOf(track("old00000001")), eightDaysAgo))
            val gathered = candidates(port, listOf(station)).gather(now)

            assertEquals(listOf("seed0000001"), port.radioCalls)
            assertEquals(8, gathered.size)
            assertTrue(gathered.all { it.source == CandidateSource.STATION })
            val saved = stationStore.cached().getValue("st1")
            assertEquals(8, saved.tracks.size)
            assertEquals(now.toEpochMilli(), saved.refreshedAt)
        }

    @Test
    fun `a station whose radio failed is not asked again for a day and counts as a failure`() =
        runBlocking {
            val station = Station("st1", "Old songs", "seed0000001", 0)
            val inner = FakePort { emptyList() }
            var asks = 0
            val failing =
                object : YouTubePlaylistPort by inner {
                    override suspend fun radio(seedVideoId: String): Result<List<MixTrack>> {
                        asks++
                        return Result.failure(IllegalStateException("radio refused"))
                    }
                }
            val failures = mutableListOf<Instant>()

            candidates(failing, listOf(station), onFetchFailed = { failures += it }).gather(now)
            candidates(failing, listOf(station), onFetchFailed = { failures += it }).gather(now.plusSeconds(3_600))

            assertEquals(1, asks)
            assertEquals(listOf(now), failures)

            candidates(failing, listOf(station), onFetchFailed = { failures += it }).gather(now.plusSeconds(25 * 3_600L))

            assertEquals(2, asks)
            assertEquals(2, failures.size)
        }

    @Test
    fun `spaces station radio fetches a minute apart`() =
        runBlocking {
            val stations = listOf(Station("a", "A", "seed0000001", 0), Station("b", "B", "seed0000002", 1), Station("c", "C", "seed0000003", 2))
            val pauses = mutableListOf<Long>()
            candidates(FakePort { listOf(track("t${it.takeLast(1)}000000001")) }, stations, pauses = pauses).gather(now)

            assertEquals(listOf(60_000L, 60_000L), pauses)
        }

    @Test
    fun `never includes local songs`() =
        runBlocking {
            val top = listOf(RankedSong(track("local:12"), true), RankedSong(track("top00000001"), false))

            val gathered = candidates(top = top, liked = listOf(meta("liked000001"))).gather(now)

            assertEquals(listOf("top00000001", "liked000001"), gathered.map { it.id })
            assertEquals(listOf(CandidateSource.TOP_PLAYED, CandidateSource.LIKED), gathered.map { it.source })
        }
}

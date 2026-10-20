/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 11 — the car's four tabs. onGetChildren hands every one of
// these parents to CarBrowseTree first, so the tree is what the car sees:
//  - the root is exactly Home, Library, Stations, Family, in that order;
//  - Library is what the root used to be (Liked / Songs / Artists / Albums /
//    Playlists, by the person's own section settings), unchanged;
//  - Home is what the YouTube-mixes folder used to hold, always offered;
//  - Stations are the cached family stations, read at once (never from the hub),
//    with Saral's Hindi names for the four starting ones, each carrying its seed
//    so choosing one plays a radio;
//  - Family is CarFamilyTree's rows (Phase 4 Task 15), asked of a function handed in, never of the hub.
// An empty cache that has not finished loading is told apart from one that has:
// both answer an empty list at once, but only the first has to ask again.
package com.metrolist.music.saraswati.car

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.music.R
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.playback.MusicService
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FamilyPlaylist
import com.metrolist.music.saraswati.family.FamilyViewModel
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.ParivaarMix
import com.metrolist.music.saraswati.family.sangat.CarSangatState
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CarBrowseTreeTest {
    // The unit-test build has no app resource table, so words are stood in for by their ids and icons by a uri that
    // names the icon: what matters here is which string and which icon each row asked for.
    private val resources = CarResources(string = { "string#$it" }, iconUri = { Uri.parse("icon://$it") })

    private fun text(id: Int) = resources.string(id)

    private val bollywood = Station("default-bollywood-classics", "Bollywood Classics", "bollywd0001", 0)
    private val bhajan = Station("default-bhajans-devotional", "Bhajans & Devotional", "bhajan00001", 2)
    private val mummy = Station("s9", "Mummy's Songs", "mummy000001", 1)

    private var sections: List<Pair<AndroidAutoSection, Boolean>> = AndroidAutoSection.values().map { it to true }
    private var home: List<MediaItem> = emptyList()
    private var sectionCalls = 0
    private var homeCalls = 0
    private var familyCalls = 0
    private var mix = ParivaarMix.EMPTY
    private var familyPlaylists = emptyList<FamilyPlaylist>()

    private fun tree(cache: FakeStationsCache = FakeStationsCache()) =
        CarBrowseTree(
            resources = resources,
            stations = cache,
            librarySections = {
                sectionCalls++
                sections
            },
            homeShelves = {
                homeCalls++
                home
            },
            familyItems = {
                familyCalls++
                CarFamilyTree.children(mix, familyPlaylists, { true }, CarSangatState.IDLE, resources)
            },
        )

    private fun children(
        tree: CarBrowseTree,
        parentId: String,
    ): List<MediaItem>? = runBlocking { withTimeout(5_000) { tree.childrenOf(parentId) } }

    private val MediaItem.title get() = mediaMetadata.title?.toString()

    // --- the root ------------------------------------------------------------------

    @Test
    fun `the root is exactly Home, Library, Stations and Family, in that order`() {
        val root = children(tree(), MusicService.ROOT)!!

        assertEquals(
            listOf(MusicService.CAR_HOME, MusicService.CAR_LIBRARY, MusicService.CAR_STATIONS, MusicService.CAR_FAMILY),
            root.map { it.mediaId },
        )
        assertEquals(
            listOf(R.string.home, R.string.filter_library, R.string.car_tab_stations, R.string.family).map(::text),
            root.map { it.title },
        )
    }

    @Test
    fun `every tab is a folder to open, never something to play`() {
        for (tab in children(tree(), MusicService.ROOT)!!) {
            assertEquals(tab.mediaId, true, tab.mediaMetadata.isBrowsable)
            assertEquals(tab.mediaId, false, tab.mediaMetadata.isPlayable)
            assertNotNull("${tab.mediaId} has an icon", tab.mediaMetadata.artworkUri)
        }
    }

    @Test
    fun `the tab ids are four different ids, none of them an older folder's`() {
        val ids = listOf(MusicService.CAR_HOME, MusicService.CAR_LIBRARY, MusicService.CAR_STATIONS, MusicService.CAR_FAMILY)

        assertEquals(4, ids.toSet().size)
        for (old in listOf(MusicService.ROOT, MusicService.SONG, MusicService.ARTIST, MusicService.ALBUM, MusicService.PLAYLIST, MusicService.YOUTUBE_PLAYLIST)) {
            assertFalse(old, old in ids)
        }
    }

    @Test
    fun `an id that is not one of the tabs is left to the rest of the browse tree`() {
        val tree = tree()

        for (other in listOf(MusicService.SONG, MusicService.ARTIST, "playlist/abc", "youtube_playlist/abc", "nonsense", "")) {
            assertNull(other, children(tree, other))
        }
    }

    // --- Library: what the root used to be ---------------------------------------------

    @Test
    fun `Library is the old root list, in the person's section order, with the old ids`() {
        val library = children(tree(), MusicService.CAR_LIBRARY)!!

        assertEquals(
            listOf("playlist/${PlaylistEntity.LIKED_PLAYLIST_ID}", MusicService.SONG, MusicService.ARTIST, MusicService.ALBUM, MusicService.PLAYLIST),
            library.map { it.mediaId },
        )
        assertEquals(
            listOf(R.string.liked_songs, R.string.songs, R.string.artists, R.string.albums, R.string.playlists).map(::text),
            library.map { it.title },
        )
        for (item in library) {
            assertEquals(item.mediaId, true, item.mediaMetadata.isBrowsable)
            assertEquals(item.mediaId, false, item.mediaMetadata.isPlayable)
        }
        assertEquals(
            listOf(
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            ),
            library.map { it.mediaMetadata.mediaType },
        )
    }

    @Test
    fun `Library follows the sections the person switched on, in their order`() {
        sections =
            listOf(
                AndroidAutoSection.ALBUMS to true,
                AndroidAutoSection.SONGS to false,
                AndroidAutoSection.LIKED to true,
                AndroidAutoSection.ARTISTS to false,
                AndroidAutoSection.PLAYLISTS to true,
            )

        val library = children(tree(), MusicService.CAR_LIBRARY)!!

        assertEquals(listOf(MusicService.ALBUM, "playlist/${PlaylistEntity.LIKED_PLAYLIST_ID}", MusicService.PLAYLIST), library.map { it.mediaId })
    }

    @Test
    fun `Library with every section switched off still offers Liked songs`() {
        sections = AndroidAutoSection.values().map { it to false }

        val library = children(tree(), MusicService.CAR_LIBRARY)!!

        assertEquals(listOf("playlist/${PlaylistEntity.LIKED_PLAYLIST_ID}"), library.map { it.mediaId })
    }

    @Test
    fun `Library no longer carries the YouTube mixes folder, which is Home now`() {
        val library = children(tree(), MusicService.CAR_LIBRARY)!!

        assertFalse(library.any { it.mediaId == MusicService.YOUTUBE_PLAYLIST })
        assertEquals("Library asks nothing of Home", 0, homeCalls)
    }

    // --- Home: what the YouTube folder used to hold ------------------------------------------

    @Test
    fun `Home is exactly the YouTube shelves, handed through untouched`() {
        val shelf = MediaItem.Builder().setMediaId("youtube_playlist/PL123").setMediaMetadata(MediaMetadata.Builder().setTitle("Mix").build()).build()
        home = listOf(shelf)

        val result = children(tree(), MusicService.CAR_HOME)!!

        assertEquals(listOf(shelf), result)
        assertEquals("Home asks nothing of Library", 0, sectionCalls)
    }

    @Test
    fun `Home is offered with no setting to switch on first`() {
        // The tree has no switch to read: whatever the shelves are, they are the answer.
        home = emptyList()

        assertEquals(emptyList<MediaItem>(), children(tree(), MusicService.CAR_HOME))
        assertEquals(1, homeCalls)
    }

    @Test
    fun `an old car that still asks for the YouTube folder gets Home`() {
        home = listOf(MediaItem.Builder().setMediaId("youtube_playlist/PL9").build())

        assertEquals(home, children(tree(), MusicService.YOUTUBE_PLAYLIST))
    }

    // --- Stations: the cache, at once -------------------------------------------------------------

    @Test
    fun `Stations are the cached stations, in order, each playable and not a folder`() {
        val stations = children(tree(FakeStationsCache(listOf(mummy, bhajan))), MusicService.CAR_STATIONS)!!

        assertEquals(listOf("Mummy's Songs", text(R.string.saral_station_bhajan)), stations.map { it.title })
        for (item in stations) {
            assertEquals(item.mediaId, true, item.mediaMetadata.isPlayable)
            assertEquals(item.mediaId, false, item.mediaMetadata.isBrowsable)
            assertNotNull(item.mediaId, item.mediaMetadata.artworkUri)
        }
    }

    @Test
    fun `the four starting stations carry Saral's Hindi names and a renamed one keeps the owner's`() {
        val starting = FamilyViewModel.DEFAULT_STATIONS.mapIndexed { i, s -> s.copy(seedVideoId = "starting%03d".format(i)) }
        val renamed = starting[1].copy(title = "Mummy's Bhajans")

        val titles = children(tree(FakeStationsCache(listOf(starting[0], renamed, starting[2], starting[3]))), MusicService.CAR_STATIONS)!!.map { it.title }

        assertEquals(
            listOf(R.string.saral_station_bollywood, null, R.string.saral_station_ghazal, R.string.saral_station_meditation)
                .map { res -> res?.let(::text) ?: renamed.title },
            titles,
        )
    }

    @Test
    fun `each station carries its seed in its id, so choosing it plays a radio seeded from it`() {
        val stations = children(tree(FakeStationsCache(listOf(bollywood, mummy))), MusicService.CAR_STATIONS)!!

        assertEquals(listOf("car_stations/0/bollywd0001", "car_stations/1/mummy000001"), stations.map { it.mediaId })
        assertEquals(listOf("bollywd0001", "mummy000001"), stations.map { CarStations.seedFrom(it.mediaId) })
    }

    @Test
    fun `two stations with the same seed still have different ids`() {
        val twin = mummy.copy(id = "s10", title = "Papa's Songs", seedVideoId = bollywood.seedVideoId)

        val ids = children(tree(FakeStationsCache(listOf(bollywood, twin))), MusicService.CAR_STATIONS)!!.map { it.mediaId }

        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `a station with no usable seed is not offered, since choosing it could play nothing`() {
        val blank = mummy.copy(seedVideoId = "")
        val short = mummy.copy(seedVideoId = "tooShort")
        val slashed = mummy.copy(seedVideoId = "abc/../12345")

        val stations = children(tree(FakeStationsCache(listOf(blank, bollywood, short, slashed))), MusicService.CAR_STATIONS)!!

        assertEquals(listOf("bollywd0001"), stations.map { CarStations.seedFrom(it.mediaId) })
    }

    @Test
    fun `the Stations folder never asks for Library or Home, or waits for anything`() {
        val never = CompletableDeferred<List<MediaItem>>()
        val hanging =
            CarBrowseTree(
                resources = resources,
                stations = FakeStationsCache(listOf(bollywood)),
                librarySections = { never.await().let { sections } },
                homeShelves = { never.await() },
                familyItems = { emptyList() },
            )

        val result = children(hanging, MusicService.CAR_STATIONS)

        assertEquals(1, result!!.size)
    }

    @Test
    fun `an empty cache that has finished loading answers an empty list, not an error`() {
        val result = children(tree(FakeStationsCache(emptyList(), loadedAtStart = true)), MusicService.CAR_STATIONS)

        assertEquals(emptyList<MediaItem>(), result)
    }

    @Test
    fun `an empty cache that has not finished loading also answers an empty list at once`() {
        val cache = FakeStationsCache(emptyList(), loadedAtStart = false)

        val result = children(tree(cache), MusicService.CAR_STATIONS)

        assertEquals(emptyList<MediaItem>(), result)
        assertFalse("answering must not have waited for it to load", cache.loaded.value)
    }

    @Test
    fun `the Stations folder reads the cache afresh each time it is asked`() {
        val cache = FakeStationsCache(emptyList(), loadedAtStart = false)
        val tree = tree(cache)
        assertEquals(0, children(tree, MusicService.CAR_STATIONS)!!.size)

        cache.finishLoading(listOf(bollywood, bhajan))

        assertEquals(2, children(tree, MusicService.CAR_STATIONS)!!.size)
    }

    // --- Family: one greyed row ----------------------------------------------------------------------

    @Test
    fun `Family is what CarFamilyTree gives, with the Sangat row last and nothing greyed`() {
        mix = ParivaarMix(1L, listOf(MixTrack("abcdefghijk", "Song", "Artist", null, 1000L)))
        familyPlaylists = listOf(FamilyPlaylist("1", "PLfamily_one-1", "Road songs", "dev1", "2026-10-19T00:00:00Z"))

        val family = children(tree(FakeStationsCache(listOf(bollywood))), MusicService.CAR_FAMILY)!!

        assertEquals(listOf("parivaar_mix", "youtube_playlist/PLfamily_one-1", "sangat_toggle"), family.map { it.mediaId })
        assertTrue(family.all { it.mediaMetadata.isPlayable == true })
    }

    @Test
    fun `Family with nothing cached is the Sangat row alone, and asks neither the sections nor Home`() {
        val family = children(tree(), MusicService.CAR_FAMILY)!!

        assertEquals(listOf("sangat_toggle"), family.map { it.mediaId })
        assertEquals(1, familyCalls)
        assertEquals(0, sectionCalls + homeCalls)
    }

    // --- choosing a station: the id is checked before it is believed -----------------------------------------

    @Test
    fun `a station id gives back its seed and nothing else`() {
        assertEquals("abcDEF_-123", CarStations.seedFrom("car_stations/3/abcDEF_-123"))
        assertEquals("bollywd0001", CarStations.seedFrom(CarStations.mediaId(0, "bollywd0001")))
    }

    @Test
    fun `an id that this app did not make gives no seed`() {
        for (bad in
            listOf(
                "", "car_stations", "car_stations/", "car_stations/0", "car_stations/0/", "car_stations/0/short",
                "car_stations/0/waytoolongforavideoid", "car_stations/x/bollywd0001", "car_stations/-1/bollywd0001",
                "car_stations/0/bollywd0001/extra", "car_home/0/bollywd0001", "song/0/bollywd0001",
                "car_stations/0/bollywd 001", "car_stations/0/bollywd0001?x", "car_stations/../bollywd0001",
            )
        ) {
            assertNull(bad, CarStations.seedFrom(bad))
        }
    }

    // --- when the car is told to ask again --------------------------------------------------------------------

    private fun collecting(
        cache: FakeStationsCache,
        block: suspend (MutableList<Int>) -> Unit,
    ) = runBlocking {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.Unconfined) { carStationsChanges(cache).toList(seen) }
        try {
            block(seen)
        } finally {
            job.cancel()
        }
    }

    private suspend fun settle() = repeat(20) { yield() }

    @Test
    fun `nothing is announced while the cache has not loaded, even though it is empty`() {
        collecting(FakeStationsCache(emptyList(), loadedAtStart = false)) { seen ->
            settle()

            assertEquals(emptyList<Int>(), seen.toList())
        }
    }

    @Test
    fun `loading that finds stations tells the car how many`() {
        val cache = FakeStationsCache(emptyList(), loadedAtStart = false)
        collecting(cache) { seen ->
            cache.finishLoading(listOf(bollywood, bhajan))
            settle()

            assertEquals(listOf(2), seen.toList())
        }
    }

    @Test
    fun `loading that finds nothing still tells the car, so it asks again and sees none`() {
        val cache = FakeStationsCache(emptyList(), loadedAtStart = false)
        collecting(cache) { seen ->
            cache.finishLoading(emptyList())
            settle()

            assertEquals(listOf(0), seen.toList())
        }
    }

    @Test
    fun `a cache that was already loaded announces what it holds once`() {
        collecting(FakeStationsCache(listOf(bollywood))) { seen ->
            settle()

            assertEquals(listOf(1), seen.toList())
        }
    }

    @Test
    fun `a changed list is announced, and the same list saved again is not`() {
        val cache = FakeStationsCache(listOf(bollywood))
        collecting(cache) { seen ->
            settle()
            cache.replace(listOf(bollywood, bhajan))
            settle()
            cache.replace(listOf(bollywood, bhajan))
            settle()
            cache.replace(listOf(bollywood.copy(title = "Old Hindi Songs"), bhajan))
            settle()

            assertEquals(listOf(1, 2, 2), seen.toList())
        }
    }

    @Test
    fun `a list that offers the car nothing it can play is announced as none`() {
        val cache = FakeStationsCache(emptyList(), loadedAtStart = false)
        collecting(cache) { seen ->
            cache.finishLoading(listOf(mummy.copy(seedVideoId = "")))
            settle()

            assertEquals(listOf(0), seen.toList())
        }
    }

    @Test
    fun `the car is told how many items it will now see`() {
        val cache = FakeStationsCache(emptyList(), loadedAtStart = false)
        collecting(cache) { seen ->
            cache.finishLoading(listOf(bollywood, mummy.copy(seedVideoId = "bad"), bhajan))
            settle()

            assertEquals(listOf(2), seen.toList())
            assertTrue(children(tree(cache), MusicService.CAR_STATIONS)!!.size == seen.last())
        }
    }
}

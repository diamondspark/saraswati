/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

// SARASWATI: artist-queue plan tests
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.innertube.pages.ArtistSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibrarySessionCallbackTest {
    @Test
    fun `browse tree parents accept subscriptions`() {
        assertTrue(isBrowsableMediaId(MusicService.ROOT))
        assertTrue(isBrowsableMediaId(MusicService.ARTIST))
        assertTrue(isBrowsableMediaId("${MusicService.ARTIST}/artist-id"))
        assertTrue(isBrowsableMediaId("${MusicService.PLAYLIST}/playlist-id"))
        assertFalse(isBrowsableMediaId("unknown"))
        assertFalse(isBrowsableMediaId("${MusicService.SONG}/song-id"))
    }

    // SARASWATI: Task 11 — the four car tabs are folders a car may subscribe to.
    @Test
    fun `the car tabs accept subscriptions`() {
        for (tab in listOf(MusicService.CAR_HOME, MusicService.CAR_LIBRARY, MusicService.CAR_STATIONS, MusicService.CAR_FAMILY)) {
            assertTrue(tab, isBrowsableMediaId(tab))
        }
    }

    @Test
    fun `a station is something to play, not a folder to subscribe to`() {
        assertFalse(isBrowsableMediaId("${MusicService.CAR_STATIONS}/0/abcdefghijk"))
        assertFalse(isBrowsableMediaId("${MusicService.CAR_FAMILY}/sangat_soon"))
    }

    @Test
    fun `children are limited to the requested page`() {
        val children = listOf("one", "two", "three", "four", "five")

        assertEquals(listOf("one", "two"), children.paginate(page = 0, pageSize = 2))
        assertEquals(listOf("three", "four"), children.paginate(page = 1, pageSize = 2))
        assertEquals(listOf("five"), children.paginate(page = 2, pageSize = 2))
        assertEquals(emptyList<String>(), children.paginate(page = 3, pageSize = 2))
    }

    @Test
    fun `database pages are capped before loading relations`() {
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 0, pageSize = Int.MAX_VALUE),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = MAX_ANDROID_AUTO_PAGE_SIZE, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 1, pageSize = 1_000),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 1_000, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 2, pageSize = 500),
        )
    }

    @Test
    fun `playlist pages account for the leading shuffle action`() {
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 99),
            androidAutoPageRequest(page = 0, pageSize = 100).afterLeadingItems(1),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 99, limit = 100),
            androidAutoPageRequest(page = 1, pageSize = 100).afterLeadingItems(1),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 499, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 1, pageSize = 1_000).afterLeadingItems(1),
        )
    }

    @Test
    fun `playlist containers account for both built in playlists`() {
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 0),
            androidAutoPageRequest(page = 0, pageSize = 1).afterLeadingItems(2),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 0),
            androidAutoPageRequest(page = 1, pageSize = 1).afterLeadingItems(2),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 1),
            androidAutoPageRequest(page = 2, pageSize = 1).afterLeadingItems(2),
        )
    }

    // SARASWATI: the "play <name>" title-mention fallback's two pure helpers.
    @Test
    fun `title mention query tokens are lowercase words of two or more letters`() {
        assertEquals(setOf("aamir", "khan"), titleMentionQueryTokens("Aamir Khan"))
        assertEquals(setOf("palan", "hare"), titleMentionQueryTokens("O Palan Hare"))
    }

    @Test
    fun `a title mentions a name only when it contains every token`() {
        assertTrue(
            titleMentionsAllTokens(
                "Zindagi Maut Na Ban Jaye | Aamir Khan | Sarfarosh",
                setOf("aamir", "khan"),
            ),
        )
        assertFalse(titleMentionsAllTokens("Bum Bum Bole", setOf("aamir", "khan")))
        assertFalse(titleMentionsAllTokens("anything", emptySet()))
    }

    // SARASWATI: the artist-queue plan behind "play <artist> songs".
    @Test
    fun `an artist with a radio endpoint plays that radio`() {
        val radio = WatchEndpoint(playlistId = "RDEM-sonu")
        val page = artistPage(radioEndpoint = radio, sections = listOf(songsSection(song("s1"), song("s2"))))

        val plan = runBlocking { artistQueuePlan("UCsonu", { Result.success(page) }) }

        assertEquals(radio, plan.radioEndpoint)
    }

    @Test
    fun `an artist without a radio uses its shuffle or play endpoint before its songs`() {
        val shuffle = WatchEndpoint(playlistId = "shuffle")
        val play = WatchEndpoint(playlistId = "play")

        val withShuffle = runBlocking {
            artistQueuePlan("UCa", { Result.success(artistPage(shuffleEndpoint = shuffle, playEndpoint = play)) })
        }
        val withPlayOnly = runBlocking {
            artistQueuePlan("UCa", { Result.success(artistPage(playEndpoint = play)) })
        }

        assertEquals(shuffle, withShuffle.radioEndpoint)
        assertEquals(play, withPlayOnly.radioEndpoint)
    }

    @Test
    fun `without an endpoint the artist's songs are used once each and only songs are kept`() {
        val artistItem = ArtistItem(id = "UCrelated", title = "Other", thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)
        val page =
            artistPage(
                radioEndpoint = null,
                sections =
                    listOf(
                        songsSection(song("s1"), song("s2"), song("s1")),
                        ArtistSection(title = "Related", items = listOf(artistItem), moreEndpoint = null),
                        songsSection(song("s3")),
                    ),
            )

        val plan = runBlocking { artistQueuePlan("UCsonu", { Result.success(page) }) }

        assertNull(plan.radioEndpoint)
        assertEquals(listOf("s1", "s2", "s3"), plan.topSongs.map { it.id })
    }

    @Test
    fun `a failed or empty artist page leaves the caller on its local fallback`() {
        val failed = runBlocking {
            artistQueuePlan("UCsonu", { Result.failure(IllegalStateException("offline")) })
        }
        val thrown = runBlocking {
            artistQueuePlan("UCsonu", { throw IllegalStateException("offline") })
        }
        val empty = runBlocking {
            artistQueuePlan("UCsonu", { Result.success(artistPage(radioEndpoint = null, sections = emptyList())) })
        }

        for (plan in listOf(failed, thrown, empty)) {
            assertNull(plan.radioEndpoint)
            assertTrue(plan.topSongs.isEmpty())
        }
    }

    @Test
    fun `a local artist id is never sent to the artist page`() {
        var fetches = 0

        val plan = runBlocking {
            artistQueuePlan(
                "FEmusic_local_artist-1234",
                {
                    fetches++
                    Result.success(artistPage(radioEndpoint = WatchEndpoint(playlistId = "RDEM")))
                },
            )
        }

        assertEquals(0, fetches)
        assertNull(plan.radioEndpoint)
        assertTrue(plan.topSongs.isEmpty())
    }

    // SARASWATI: the voice-search radio plan behind "play <song or artist> songs" when the radio is used.
    @Test
    fun `a seed that is in its radio mix starts at its place in the mix`() {
        val plan = radioPlayPlan("seed", "seed", listOf("a", "seed", "b")) { it }!!

        assertEquals(listOf("a", "seed", "b"), plan.items)
        assertEquals(1, plan.startIndex)
    }

    @Test
    fun `a seed missing from its radio mix plays first and the mix follows`() {
        val plan = radioPlayPlan("seed", "seed", listOf("a", "b", "c")) { it }!!

        assertEquals(listOf("seed", "a", "b", "c"), plan.items)
        assertEquals(0, plan.startIndex)
        assertTrue(plan.items.size > 1)
    }

    @Test
    fun `a seed listed twice in its radio mix is not duplicated`() {
        val plan = radioPlayPlan("seed", "seed", listOf("seed", "a", "seed", "b")) { it }!!

        assertEquals(listOf("seed", "a", "seed", "b"), plan.items)
        assertEquals(0, plan.startIndex)
    }

    @Test
    fun `an empty radio mix gives no plan so the seed plays alone`() {
        assertNull(radioPlayPlan("seed", "seed", emptyList<String>()) { it })
    }

    private fun artistPage(
        radioEndpoint: WatchEndpoint? = null,
        shuffleEndpoint: WatchEndpoint? = null,
        playEndpoint: WatchEndpoint? = null,
        sections: List<ArtistSection> = emptyList(),
    ) = ArtistPage(
        artist =
            ArtistItem(
                id = "UCsonu",
                title = "Sonu Nigam",
                thumbnail = null,
                playEndpoint = playEndpoint,
                shuffleEndpoint = shuffleEndpoint,
                radioEndpoint = radioEndpoint,
            ),
        sections = sections,
        description = null,
        subscriberCountText = null,
    )

    private fun songsSection(vararg songs: SongItem) =
        ArtistSection(title = "Songs", items = songs.toList(), moreEndpoint = null)

    private fun song(id: String) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = "")
}

/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave P5a C1 — a song on this phone (isLocal = 1) must not come out of the upstream search, liked,
// most played, quick picks, forgotten favourites or history queries, whatever the Local files switch says: the car's
// voice search, Home and the history screens would otherwise show its title with the switch off. A remote song
// is returned by each of the same queries, so an empty answer cannot be the query failing.
package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.RelatedSongMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalSongsHiddenQueriesTest {
    private lateinit var database: InternalDatabase
    private val dao get() = database.dao
    private val now = LocalDateTime.now()
    private val epoch = LocalDateTime.of(1970, 1, 1, 0, 0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        val liked = now.minusDays(1)
        dao.insert(SongEntity(id = "yt1", title = "Remote tune", thumbnailUrl = "t", liked = true, likedDate = liked, inLibrary = liked))
        dao.insert(SongEntity(id = "yt2", title = "Another remote", thumbnailUrl = "t", inLibrary = liked))
        dao.insert(
            SongEntity(id = "local:7", title = "Private recording", thumbnailUrl = "t", liked = true, likedDate = liked, isLocal = true, inLibrary = null),
        )
        dao.insert(ArtistEntity(id = "a-remote", name = "Remote Artist"))
        dao.insert(ArtistEntity(id = "a-local", name = "Local Artist", isLocal = true))
        dao.insert(SongArtistMap(songId = "yt1", artistId = "a-remote", position = 0))
        dao.insert(SongArtistMap(songId = "yt2", artistId = "a-remote", position = 0))
        dao.insert(SongArtistMap(songId = "local:7", artistId = "a-local", position = 0))
        // Recent plays, and old plays much longer than the recent ones, for the forgotten-favourites query.
        for (id in listOf("yt1", "local:7")) {
            dao.insert(Event(songId = id, timestamp = now.minusMinutes(5), playTime = 1_000))
            dao.insert(Event(songId = id, timestamp = now.minusDays(90), playTime = 500_000))
        }
        dao.insert(RelatedSongMap(songId = "yt1", relatedSongId = "yt2"))
        dao.insert(RelatedSongMap(songId = "yt1", relatedSongId = "local:7"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun assertOnlyRemote(
        label: String,
        ids: List<String>,
    ) {
        assertTrue("$label returned a local song: $ids", ids.none { it.startsWith("local:") })
        assertTrue("$label returned no remote song at all: $ids", ids.isNotEmpty())
    }

    @Test
    fun `search never returns a local song, by title, artist or album`() =
        runBlocking {
            assertEquals(emptyList<String>(), dao.searchSongsExtended("Private").first().map { it.id })
            assertEquals(emptyList<String>(), dao.searchSongsExtended("Local Artist").first().map { it.id })
            assertOnlyRemote("search", dao.searchSongsExtended("Remote").first().map { it.id })
        }

    @Test
    fun `liked lists and count leave local songs out`() =
        runBlocking {
            assertOnlyRemote("rowId", dao.likedSongsByRowIdAsc().first().map { it.id })
            assertOnlyRemote("createDate", dao.likedSongsByCreateDateAsc().first().map { it.id })
            assertOnlyRemote("createDateDesc", dao.likedSongsByCreateDateDesc(50, 0).map { it.id })
            assertOnlyRemote("name", dao.likedSongsByNameAsc().first().map { it.id })
            assertOnlyRemote("playTime", dao.likedSongsByPlayTimeAsc().first().map { it.id })
            assertEquals(1, dao.likedSongsCount().first())
        }

    @Test
    fun `most played leaves local songs and their artists out`() =
        runBlocking {
            assertOnlyRemote("mostPlayedSongs", dao.mostPlayedSongs(epoch, 10, 0, now.plusDays(1)).first().map { it.id })
            assertOnlyRemote("mostPlayedSongsStats", dao.mostPlayedSongsStats(epoch, 10, 0, now.plusDays(1)).first().map { it.id })
            assertOnlyRemote("byArtist", dao.mostPlayedSongsByArtist("a-remote", epoch, now.plusDays(1)).first().map { it.id })
            assertEquals(
                emptyList<String>(),
                dao.mostPlayedSongsByArtist("a-local", epoch, now.plusDays(1)).first().map { it.id },
            )
            val artists = dao.mostPlayedArtists(epoch, 10, 0, now.plusDays(1)).first().map { it.id }
            assertEquals(listOf("a-remote"), artists)
        }

    @Test
    fun `quick picks and forgotten favourites leave local songs out`() =
        runBlocking {
            assertEquals(listOf("yt2"), dao.quickPicks().first().map { it.id })
            assertEquals(listOf("yt1"), dao.forgottenFavorites().first().map { it.id })
        }

    @Test
    fun `history shows no local play`() =
        runBlocking {
            val history =
                dao.historyEvents(
                    tomorrowStart = now.plusDays(1),
                    todayStart = now.minusHours(12),
                    yesterdayStart = now.minusDays(2),
                    thisMondayStart = now.minusDays(3),
                    lastMondayStart = now.minusDays(10),
                ).first()
            assertOnlyRemote("history", history.map { it.song.id })
            assertTrue(dao.firstEvent().first()?.song?.id == "yt1")
            assertTrue(dao.latestEvent().first()?.song?.id == "yt1")
        }
}

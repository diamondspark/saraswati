/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 10 — a festival's radio starts the way a station tile does:
// the festival's search finds a seed song, and the radio is that song's mix.
// What the person is told when the search finds nothing or cannot run is the
// caller's to say; this pins the three answers it can give.
package com.metrolist.music.saraswati.culture

import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.saraswati.saral.FakeSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class FestivalRadioTest {
    private val diwali = FestivalCalendar.all.first { it.name == "Diwali" }
    private val search = FakeSearch()
    private val radio = FestivalRadio(search)

    private fun song(id: String) = SongItem(id = id, title = "Song $id", artists = emptyList(), thumbnail = "")

    @Test
    fun `it searches the festival's own query and seeds from the first song`() {
        search.answers["Diwali songs"] = Result.success(song("seedDiwali01"))

        val seed = runBlocking { radio.seedFor(diwali) }

        assertEquals(listOf("Diwali songs"), search.queries.toList())
        assertEquals(FestivalSeed.Found("seedDiwali01"), seed)
    }

    @Test
    fun `a search with no song is not found, which is not a failure`() {
        search.answers["Diwali songs"] = Result.success(null)

        assertEquals(FestivalSeed.NotFound, runBlocking { radio.seedFor(diwali) })
    }

    @Test
    fun `a search that could not run for want of a network says it was offline`() {
        for (failure in listOf<Throwable>(IOException("no route"), UnknownHostException("youtube"), RuntimeException("wrapped", IOException("cause")))) {
            search.answers["Diwali songs"] = Result.failure(failure)

            assertEquals(failure.toString(), FestivalSeed.Failed(offline = true), runBlocking { radio.seedFor(diwali) })
        }
    }

    @Test
    fun `any other failed search says it failed, not that it was offline`() {
        search.answers["Diwali songs"] = Result.failure(IllegalStateException("bad response"))

        assertEquals(FestivalSeed.Failed(offline = false), runBlocking { radio.seedFor(diwali) })
    }

    @Test
    fun `the radio is the seed song's mix, as a station tile's is`() {
        assertEquals(WatchEndpoint(videoId = "seedDiwali01", playlistId = "RDAMVMseedDiwali01"), radioEndpointFor("seedDiwali01"))
    }
}

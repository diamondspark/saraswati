/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

// SARASWATI: voice candidates built from the search result when the DB cache write gives nothing.
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.extensions.toMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceCandidateTest {
    @Test
    fun `a voice candidate keeps the title, duration, thumbnail, album and artists of the search result`() {
        val candidate =
            SongItem(
                id = "vid1",
                title = "Chaiyya Chaiyya",
                artists = listOf(Artist(name = "Sukhwinder Singh", id = "UCsukhwinder")),
                album = Album(name = "Dil Se", id = "MPREb_dilse"),
                duration = 265,
                thumbnail = "https://i.ytimg.com/vi/vid1/hq.jpg",
            ).toVoiceCandidate()

        assertEquals("vid1", candidate.id)
        assertEquals("Chaiyya Chaiyya", candidate.song.title)
        assertEquals(265, candidate.song.duration)
        assertEquals("https://i.ytimg.com/vi/vid1/hq.jpg", candidate.song.thumbnailUrl)
        assertEquals("MPREb_dilse", candidate.song.albumId)
        assertEquals("Dil Se", candidate.song.albumName)
        assertEquals(listOf("Sukhwinder Singh"), candidate.artists.map { it.name })
    }

    @Test
    fun `a voice candidate keeps a real YouTube artist id exactly`() {
        val candidate =
            SongItem(
                id = "vid2",
                title = "Song",
                artists = listOf(Artist(name = "Sonu Nigam", id = "UCsonu123")),
                thumbnail = "",
            ).toVoiceCandidate()

        assertEquals(listOf("UCsonu123"), candidate.artists.map { it.id })
    }

    @Test
    fun `a voice candidate with a null artist id gets a fallback id that is not a YouTube id`() {
        val candidate =
            SongItem(
                id = "vid3",
                title = "Song",
                artists = listOf(Artist(name = "Unknown Singer", id = null)),
                thumbnail = "",
            ).toVoiceCandidate()

        val fallbackId = candidate.artists.single().id
        assertNotNull(fallbackId)
        assertFalse(fallbackId.startsWith("UC"))
    }

    @Test
    fun `two songs by the same null-id artist share one fallback artist id`() {
        val first =
            SongItem(
                id = "vid5",
                title = "Song A",
                artists = listOf(Artist(name = "Unknown Singer", id = null)),
                thumbnail = "",
            ).toVoiceCandidate()
        val second =
            SongItem(
                id = "vid6",
                title = "Song B",
                artists = listOf(Artist(name = " unknown singer ", id = null)),
                thumbnail = "",
            ).toVoiceCandidate()

        assertEquals(first.artists.single().id, second.artists.single().id)
        assertFalse(first.artists.single().id.startsWith("UC"))
    }

    @Test
    fun `a voice candidate can be turned into a media item`() {
        val candidate =
            SongItem(
                id = "vid4",
                title = "Song",
                artists = listOf(Artist(name = "Sonu Nigam", id = "UCsonu123")),
                album = Album(name = "Album", id = "MPREb"),
                duration = 200,
                thumbnail = "https://i.ytimg.com/vi/vid4/hq.jpg",
            ).toVoiceCandidate()

        assertEquals("vid4", candidate.toMediaItem().mediaId)
    }
}

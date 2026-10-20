/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.spotifyimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportifyCsvParserTest {
    @Test
    fun `maps columns by header in any order`() {
        val csv =
            """
            Album Name,Duration (ms),Track Name,Artist Name(s)
            Abbey Road,215000,Come Together,The Beatles
            """.trimIndent()

        val playlist = ExportifyCsvParser.parse(csv.byteInputStream(), "Mix").getOrThrow()

        assertEquals("Mix", playlist.name)
        assertEquals(
            listOf(ImportedTrack("Come Together", listOf("The Beatles"), "Abbey Road", 215000L)),
            playlist.tracks,
        )
    }

    @Test
    fun `splits Artist Name(s) on commas`() {
        val csv =
            "Track Name,Artist Name(s),Album Name,Duration (ms)\n" +
                "Song,\"First, Second\",Album,1000\n"

        val track = ExportifyCsvParser.parse(csv.byteInputStream(), "Mix").getOrThrow().tracks.single()

        assertEquals(listOf("First", "Second"), track.artists)
    }

    @Test
    fun `handles quoted commas and doubled quotes`() {
        val csv =
            "Track Name,Artist Name(s),Album Name,Duration (ms)\r\n" +
                "\"Hello, \"\"World\"\"\",Artist,Album,\r\n"

        val track = ExportifyCsvParser.parse(csv.byteInputStream(), "Mix").getOrThrow().tracks.single()

        assertEquals("Hello, \"World\"", track.title)
        assertEquals(null, track.durationMs)
    }

    @Test
    fun `missing Track Name is UnsupportedFormat`() {
        val csv = "Artist Name(s),Album Name\nArtist,Album\n"

        val error = ExportifyCsvParser.parse(csv.byteInputStream(), "Mix").exceptionOrNull()

        assertTrue(error is ParseError.UnsupportedFormat)
    }
}

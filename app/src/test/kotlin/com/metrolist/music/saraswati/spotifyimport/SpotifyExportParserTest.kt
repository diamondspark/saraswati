/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.spotifyimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpotifyExportParserTest {
    @Test
    fun `reads playlists and tracks from Playlist1 json`() {
        val playlists =
            SpotifyExportParser
                .parse(PLAYLIST_JSON.byteInputStream(), "Playlist1.json")
                .getOrThrow()

        assertEquals(1, playlists.size)
        assertEquals("Road Trip", playlists.single().name)
        assertEquals(
            listOf(
                ImportedTrack(title = "Song A", artists = listOf("Artist A"), album = "Album A", durationMs = null),
                ImportedTrack(title = "Song B", artists = listOf("Artist B"), album = null, durationMs = null),
            ),
            playlists.single().tracks,
        )
    }

    @Test
    fun `skips items whose track is null`() {
        val playlists =
            SpotifyExportParser
                .parse(PLAYLIST_JSON.byteInputStream(), "Playlist1.json")
                .getOrThrow()

        assertEquals(2, playlists.single().tracks.size)
    }

    @Test
    fun `reads YourLibrary json as Spotify liked songs`() {
        val playlists =
            SpotifyExportParser
                .parse(LIBRARY_JSON.byteInputStream(), "YourLibrary.json")
                .getOrThrow()

        assertEquals(listOf("Spotify liked songs"), playlists.map { it.name })
        assertEquals(
            listOf(ImportedTrack(title = "Liked One", artists = listOf("Band"), album = "LP", durationMs = null)),
            playlists.single().tracks,
        )
    }

    @Test
    fun `reads playlists inside the export zip without writing to disk`() {
        val zip = zipOf("Spotify Account Data/Playlist1.json" to PLAYLIST_JSON.toByteArray())

        val playlists = SpotifyExportParser.parse(zip.inputStream(), "my-export.zip").getOrThrow()

        assertEquals(listOf("Road Trip"), playlists.map { it.name })
    }

    @Test
    fun `rejects a zip that inflates past 50 MB`() {
        val bomb = ByteArray(51 * 1024 * 1024)
        val zip = zipOf("Playlist1.json" to bomb)

        val error = SpotifyExportParser.parse(zip.inputStream(), "bomb.zip").exceptionOrNull()

        assertTrue(error is ParseError.ZipTooLarge)
    }

    @Test
    fun `rejects a file over 20 MB`() {
        val huge = ByteArray(20 * 1024 * 1024 + 1) { ' '.code.toByte() }

        val error = SpotifyExportParser.parse(huge.inputStream(), "Playlist1.json").exceptionOrNull()

        assertTrue(error is ParseError.FileTooLarge)
    }

    @Test
    fun `unrelated JSON is UnsupportedFormat`() {
        val error =
            SpotifyExportParser
                .parse("""{"hello":"world"}""".byteInputStream(), "notes.json")
                .exceptionOrNull()

        assertTrue(error is ParseError.UnsupportedFormat)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        val PLAYLIST_JSON =
            """
            {"playlists":[{"name":"Road Trip","items":[
              {"track":{"trackName":"Song A","artistName":"Artist A","albumName":"Album A","trackUri":"spotify:track:a"}},
              {"track":null},
              {"track":{"trackName":"Song B","artistName":"Artist B","albumName":null,"trackUri":"spotify:track:b"}}
            ]}]}
            """.trimIndent()

        val LIBRARY_JSON =
            """{"tracks":[{"artist":"Band","album":"LP","track":"Liked One","uri":"spotify:track:c"}]}"""
    }
}

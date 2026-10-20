/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.spotifyimport

import java.io.InputStream

/**
 * Reads an Exportify CSV (one playlist per file). Columns are matched by header name, so their
 * order does not matter. Fields follow RFC 4180 quoting.
 */
object ExportifyCsvParser {
    private const val TRACK_NAME = "Track Name"
    private const val ARTIST_NAMES = "Artist Name(s)"
    private const val ALBUM_NAME = "Album Name"
    private const val DURATION_MS = "Duration (ms)"

    fun parse(
        input: InputStream,
        name: String,
    ): Result<ImportedPlaylist> =
        runCatching {
            val text = String(input.readCapped(SpotifyImportLimits.MAX_FILE_BYTES), Charsets.UTF_8).removePrefix("﻿")
            val rows = parseRows(text)
            val header = rows.firstOrNull()?.map(String::trim) ?: throw ParseError.UnsupportedFormat
            val trackIndex = header.indexOf(TRACK_NAME).takeIf { it >= 0 } ?: throw ParseError.UnsupportedFormat
            val artistIndex = header.indexOf(ARTIST_NAMES)
            val albumIndex = header.indexOf(ALBUM_NAME)
            val durationIndex = header.indexOf(DURATION_MS)

            val tracks =
                rows
                    .drop(1)
                    .mapNotNull { row ->
                        val title = row.cell(trackIndex) ?: return@mapNotNull null
                        ImportedTrack(
                            title = title,
                            artists = row.cell(artistIndex).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
                            album = row.cell(albumIndex),
                            durationMs = row.cell(durationIndex)?.toLongOrNull(),
                        )
                    }.take(SpotifyImportLimits.MAX_TRACKS)

            ImportedPlaylist(name = name, tracks = tracks)
        }

    private fun List<String>.cell(index: Int): String? = if (index < 0) null else getOrNull(index)?.trim()?.takeIf(String::isNotEmpty)

    /** RFC 4180 rows: quoted fields may hold commas, line breaks and doubled quotes. Blank lines are skipped. */
    internal fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun endRow() {
            row += field.toString()
            field.clear()
            if (row.size > 1 || row.first().isNotEmpty()) rows += row
            row = mutableListOf()
        }

        while (index < text.length) {
            val char = text[index]
            if (inQuotes) {
                when {
                    char != '"' -> field.append(char)
                    index + 1 < text.length && text[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }
                    else -> inQuotes = false
                }
            } else {
                when (char) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row += field.toString()
                        field.clear()
                    }
                    '\r' -> {
                        if (index + 1 < text.length && text[index + 1] == '\n') index++
                        endRow()
                    }
                    '\n' -> endRow()
                    else -> field.append(char)
                }
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}

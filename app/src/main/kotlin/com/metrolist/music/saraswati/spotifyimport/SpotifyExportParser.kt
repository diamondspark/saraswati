/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.spotifyimport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ImportedPlaylist(
    val name: String,
    val tracks: List<ImportedTrack>,
)

data class ImportedTrack(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
)

sealed class ParseError(
    message: String,
) : Exception(message) {
    object UnsupportedFormat : ParseError("Unsupported Spotify export format")

    object FileTooLarge : ParseError("Import file is larger than 20 MB")

    object ZipTooLarge : ParseError("Import zip inflates to more than 50 MB")

    object TooManyEntries : ParseError("Import zip has more than 200 entries")
}

internal object SpotifyImportLimits {
    const val MAX_FILE_BYTES = 20 * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 200
    const val MAX_ZIP_INFLATED_BYTES = 50L * 1024 * 1024
    const val MAX_TRACKS = 10_000
}

/** Tracks left to parse across every playlist in one import. */
internal class TrackCap(
    private var remaining: Int = SpotifyImportLimits.MAX_TRACKS,
) {
    fun take(count: Int): Int {
        val granted = minOf(count, remaining)
        remaining -= granted
        return granted
    }
}

object SpotifyExportParser {
    private val playlistFile = Regex("Playlist\\d*\\.json", RegexOption.IGNORE_CASE)
    private const val LIBRARY_FILE = "YourLibrary.json"
    private const val LIKED_SONGS_NAME = "Spotify liked songs"

    fun parse(
        input: InputStream,
        fileName: String,
    ): Result<List<ImportedPlaylist>> =
        runCatching {
            val bytes = input.readCapped(SpotifyImportLimits.MAX_FILE_BYTES)
            val cap = TrackCap()
            when {
                bytes.isZip() -> parseZip(bytes, cap)
                isExportJson(fileName) -> parseJsonFile(bytes, fileName, cap)
                else -> throw ParseError.UnsupportedFormat
            }
        }

    private fun isExportJson(fileName: String): Boolean {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        return playlistFile.matches(name) || name.equals(LIBRARY_FILE, ignoreCase = true)
    }

    private fun parseZip(
        bytes: ByteArray,
        cap: TrackCap,
    ): List<ImportedPlaylist> {
        val playlists = mutableListOf<ImportedPlaylist>()
        val budget = InflateBudget()
        var entryCount = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > SpotifyImportLimits.MAX_ZIP_ENTRIES) throw ParseError.TooManyEntries
                if (entry.isDirectory) continue

                val name = entry.name.substringAfterLast('/')
                val wanted = isExportJson(name)
                val content = zip.readInflated(keep = wanted, budget = budget)
                if (wanted) playlists += parseJsonFile(content, name, cap)
            }
        }
        if (playlists.isEmpty()) throw ParseError.UnsupportedFormat
        return playlists
    }

    private fun parseJsonFile(
        bytes: ByteArray,
        fileName: String,
        cap: TrackCap,
    ): List<ImportedPlaylist> {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        val root = parseObject(bytes) ?: throw ParseError.UnsupportedFormat

        return when {
            playlistFile.matches(name) -> parsePlaylists(root, cap)
            name.equals(LIBRARY_FILE, ignoreCase = true) -> listOf(parseLibrary(root, cap))
            else -> throw ParseError.UnsupportedFormat
        }
    }

    private fun parsePlaylists(
        root: JsonObject,
        cap: TrackCap,
    ): List<ImportedPlaylist> {
        val playlists = root["playlists"].arrayOrNull() ?: throw ParseError.UnsupportedFormat
        return playlists.mapNotNull { element ->
            val playlist = element.jsonObjectOrNull() ?: return@mapNotNull null
            val name = playlist.string("name") ?: "Spotify playlist"
            val tracks =
                playlist["items"].arrayOrNull().orEmpty().mapNotNull { item ->
                    val track = item.jsonObjectOrNull()?.get("track")?.jsonObjectOrNull() ?: return@mapNotNull null
                    val title = track.string("trackName") ?: return@mapNotNull null
                    ImportedTrack(
                        title = title,
                        artists = listOfNotNull(track.string("artistName")),
                        album = track.string("albumName"),
                        durationMs = null,
                    )
                }
            ImportedPlaylist(name = name, tracks = tracks.take(cap.take(tracks.size)))
        }
    }

    private fun parseLibrary(
        root: JsonObject,
        cap: TrackCap,
    ): ImportedPlaylist {
        val entries = root["tracks"].arrayOrNull() ?: throw ParseError.UnsupportedFormat
        val tracks =
            entries.mapNotNull { element ->
                val entry = element.jsonObjectOrNull() ?: return@mapNotNull null
                val title = entry.string("track") ?: return@mapNotNull null
                ImportedTrack(
                    title = title,
                    artists = listOfNotNull(entry.string("artist")),
                    album = entry.string("album"),
                    durationMs = null,
                )
            }
        return ImportedPlaylist(name = LIKED_SONGS_NAME, tracks = tracks.take(cap.take(tracks.size)))
    }
}

private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

private fun ByteArray.isZip(): Boolean = size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { this[it] == ZIP_MAGIC[it] }

private fun parseObject(bytes: ByteArray): JsonObject? =
    try {
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
    } catch (error: IllegalArgumentException) {
        null
    }

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)

/** Reads the whole stream, failing as soon as it would exceed [limit] bytes. */
internal fun InputStream.readCapped(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (out.size() + read > limit) throw ParseError.FileTooLarge
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** Inflated bytes left for the whole zip, shared by every entry. */
private class InflateBudget(
    var remaining: Long = SpotifyImportLimits.MAX_ZIP_INFLATED_BYTES,
)

/**
 * Drains the current zip entry. Every byte counts against the shared inflate budget, kept or not,
 * so a bomb in an entry we ignore still fails. Returns the bytes only when [keep] is set.
 */
private fun ZipInputStream.readInflated(
    keep: Boolean,
    budget: InflateBudget,
): ByteArray {
    val out = if (keep) ByteArrayOutputStream() else null
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        budget.remaining -= read
        if (budget.remaining < 0) throw ParseError.ZipTooLarge
        out?.write(buffer, 0, read)
    }
    return out?.toByteArray() ?: ByteArray(0)
}

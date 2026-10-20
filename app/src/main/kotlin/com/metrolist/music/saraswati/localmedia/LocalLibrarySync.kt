/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 3 — turns MediaStore rows into `song` rows. Pinned mapping:
//   id = local:<MediaStore id>; title = the tag, else the file name without its extension; duration in seconds;
//   isLocal = true and inLibrary = null (so upstream's library and sync flows never list or send them);
//   artists split on ';' and ','; "<unknown>" artists and albums dropped.
// Songs whose file vanished, or that now fail LocalLibraryFilter, are deleted; foreign-key cascades remove their
// artist maps and listen history. A song whose file has not changed is not touched.
package com.metrolist.music.saraswati.localmedia

import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
import java.time.ZoneOffset

data class LocalSyncReport(
    val added: Int,
    val updated: Int,
    val removed: Int,
)

class LocalLibrarySync(
    private val store: LocalSongStore,
) {
    suspend fun sync(rows: List<MediaStoreAudioRow>): LocalSyncReport {
        val accepted = rows.filter(LocalLibraryFilter::accept).distinctBy { it.id }
        val existing = store.localSongStamps()
        val kept = HashSet<String>(accepted.size * 2)
        var added = 0
        var updated = 0
        for (row in accepted) {
            val song = toSongEntity(row)
            kept += song.id
            if (!existing.containsKey(song.id)) {
                store.upsert(song, artistNames(row.artist))
                added++
            } else if (existing[song.id] != song.dateModified) {
                store.upsert(song, artistNames(row.artist))
                updated++
            }
        }
        val gone = existing.keys.filterNot { it in kept }
        if (gone.isNotEmpty()) store.deleteLocal(gone)
        return LocalSyncReport(added = added, updated = updated, removed = gone.size)
    }

    internal companion object {
        const val UNKNOWN = "<unknown>"

        fun toSongEntity(row: MediaStoreAudioRow): SongEntity =
            SongEntity(
                id = LocalSongIds.of(row.id),
                title = titleOf(row),
                duration = (row.durationMs / 1000L).toInt(),
                thumbnailUrl = row.albumId?.let { "content://media/external/audio/albums/$it" },
                albumName = row.album?.trim()?.takeIf { it.isNotEmpty() && !it.equals(UNKNOWN, ignoreCase = true) },
                dateModified = LocalDateTime.ofEpochSecond(row.dateModifiedSec, 0, ZoneOffset.UTC),
                isLocal = true,
                inLibrary = null,
            )

        fun titleOf(row: MediaStoreAudioRow): String {
            row.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val name = row.displayName?.trim().orEmpty()
            val bare = if ('.' in name) name.substringBeforeLast('.') else name
            return bare.ifBlank { "Unknown" }
        }

        fun artistNames(artist: String?): List<String> =
            artist
                .orEmpty()
                .split(';', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(UNKNOWN, ignoreCase = true) }
                .distinct()
    }
}

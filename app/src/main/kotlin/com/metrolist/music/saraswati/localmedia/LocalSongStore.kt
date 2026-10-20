/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 3 — where synced local songs are kept: Room, through the DAO queries added in this task.
// Artists are made the way SongMenu's edit makes them: an existing artist of that name, else a new local one.
package com.metrolist.music.saraswati.localmedia

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime

/** What the store holds of a local song that is needed to tell whether its file changed. */
data class LocalSongStamp(
    val id: String,
    val dateModified: LocalDateTime?,
)

interface LocalSongStore {
    suspend fun localSongStamps(): Map<String, LocalDateTime?>

    suspend fun localSongIds(): List<String> = localSongStamps().keys.toList()

    suspend fun upsert(
        song: SongEntity,
        artistNames: List<String>,
    )

    suspend fun deleteLocal(ids: List<String>)
}

class RoomLocalSongStore(
    private val database: MusicDatabase,
) : LocalSongStore {
    override suspend fun localSongStamps(): Map<String, LocalDateTime?> =
        database.localSongStamps().associate { it.id to it.dateModified }

    override suspend fun localSongIds(): List<String> = database.localSongIds()

    override suspend fun upsert(
        song: SongEntity,
        artistNames: List<String>,
    ) {
        database.withTransaction {
            val existing = songEntity(song.id)
            // A refresh brings in what the file says; what the person did with the song (likes, play time) stays.
            val merged =
                existing?.copy(
                    title = song.title,
                    duration = song.duration,
                    thumbnailUrl = song.thumbnailUrl,
                    albumName = song.albumName,
                    dateModified = song.dateModified,
                    isLocal = true,
                ) ?: song
            upsert(merged)
            replaceSongArtists(
                song.id,
                artistNames.map { name ->
                    artistByName(name) ?: ArtistEntity(id = ArtistEntity.generateArtistId(), name = name, isLocal = true)
                },
            )
        }
    }

    override suspend fun deleteLocal(ids: List<String>) {
        ids.chunked(DELETE_CHUNK).forEach { database.deleteLocalSongs(it) }
    }

    private companion object {
        // Well under SQLite's bound-variable limit on every Android version.
        const val DELETE_CHUNK = 500
    }
}

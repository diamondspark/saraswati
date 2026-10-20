/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix pass 2 (O-2) — a local song's id (`local:<MediaStore id>`) names a file on the phone that made the
// backup, so it must not travel in a backup, and a restore must not map another phone's `local:<n>` onto this phone's
// files. This removes every local-song row from a song.db file, and its references, then rewrites the file so the
// removed rows' bytes are gone too. Callers run it only on a copy (export) or on the staged restore file, and fail
// closed when it returns false.
package com.metrolist.music.saraswati.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File

object LocalRowScrubber {
    private const val LOCAL = "local:%"

    // Child rows first. Local artists and albums (isLocal = 1) hold only names and are left alone: no song id is in them.
    // Each statement names its table, so an older restored schema that lacks a table is skipped, not failed.
    private val statements =
        listOf(
            "event" to "DELETE FROM event WHERE songId LIKE '$LOCAL'",
            "playlist_song_map" to "DELETE FROM playlist_song_map WHERE songId LIKE '$LOCAL'",
            "related_song_map" to "DELETE FROM related_song_map WHERE songId LIKE '$LOCAL' OR relatedSongId LIKE '$LOCAL'",
            "song_artist_map" to "DELETE FROM song_artist_map WHERE songId LIKE '$LOCAL'",
            "song_album_map" to "DELETE FROM song_album_map WHERE songId LIKE '$LOCAL'",
            "format" to "DELETE FROM format WHERE id LIKE '$LOCAL'",
            "lyrics" to "DELETE FROM lyrics WHERE id LIKE '$LOCAL'",
            "playCount" to "DELETE FROM playCount WHERE song LIKE '$LOCAL'",
            "set_video_id" to "DELETE FROM set_video_id WHERE videoId LIKE '$LOCAL'",
            "song" to "DELETE FROM song WHERE id LIKE '$LOCAL'",
        )

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }

    /** Scrubs [file] in place. Returns false, and leaves the file possibly half-changed, if anything fails. */
    fun scrubFile(file: File): Boolean =
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.beginTransaction()
                try {
                    statements.forEach { (table, sql) -> if (hasTable(db, table)) db.execSQL(sql) }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                // Deleted pages stay in the file until it is rewritten, and a backup carries the file as it is.
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                db.execSQL("VACUUM")
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
            true
        } catch (e: Exception) {
            false
        }
}

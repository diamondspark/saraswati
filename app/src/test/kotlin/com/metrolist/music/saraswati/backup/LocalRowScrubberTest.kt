/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix pass 2 (O-2) — the backup export and the restore both run LocalRowScrubber on a song.db file. These
// tests build a small song.db in WAL mode with local and YouTube rows, scrub it, and check that the local rows, their
// references, and their bytes are gone while the YouTube rows survive.
package com.metrolist.music.saraswati.backup

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalRowScrubberTest {
    private lateinit var dir: File
    private lateinit var db: File

    private val secretTitle = "SECRETLOCALTITLE7731"

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "scrub-${System.nanoTime()}").apply { mkdirs() }
        db = File(dir, "song.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).use { conn ->
            conn.enableWriteAheadLogging()
            conn.execSQL("CREATE TABLE song (id TEXT PRIMARY KEY, title TEXT, isLocal INTEGER)")
            conn.execSQL("CREATE TABLE event (id INTEGER PRIMARY KEY, songId TEXT)")
            conn.execSQL("CREATE TABLE playlist_song_map (id INTEGER PRIMARY KEY, playlistId TEXT, songId TEXT)")
            conn.execSQL("CREATE TABLE related_song_map (id INTEGER PRIMARY KEY, songId TEXT, relatedSongId TEXT)")
            conn.execSQL("CREATE TABLE song_artist_map (songId TEXT, artistId TEXT)")
            conn.execSQL("CREATE TABLE song_album_map (songId TEXT, albumId TEXT)")
            conn.execSQL("CREATE TABLE format (id TEXT PRIMARY KEY)")
            conn.execSQL("CREATE TABLE lyrics (id TEXT PRIMARY KEY, lyrics TEXT)")
            conn.execSQL("CREATE TABLE playCount (id INTEGER PRIMARY KEY, song TEXT)")
            conn.execSQL("CREATE TABLE set_video_id (videoId TEXT)")
            conn.execSQL("INSERT INTO song VALUES ('local:42', '$secretTitle', 1)")
            conn.execSQL("INSERT INTO song VALUES ('dQw4w9WgXcQ', 'Never Gonna', 0)")
            conn.execSQL("INSERT INTO event (songId) VALUES ('local:42'), ('dQw4w9WgXcQ')")
            conn.execSQL("INSERT INTO playlist_song_map (playlistId, songId) VALUES ('PL1', 'local:42'), ('PL1', 'dQw4w9WgXcQ')")
            conn.execSQL("INSERT INTO related_song_map (songId, relatedSongId) VALUES ('local:42', 'dQw4w9WgXcQ'), ('dQw4w9WgXcQ', 'local:42')")
            conn.execSQL("INSERT INTO song_artist_map VALUES ('local:42', 'A1'), ('dQw4w9WgXcQ', 'A2')")
            conn.execSQL("INSERT INTO song_album_map VALUES ('local:42', 'B1'), ('dQw4w9WgXcQ', 'B2')")
            conn.execSQL("INSERT INTO format VALUES ('local:42'), ('dQw4w9WgXcQ')")
            conn.execSQL("INSERT INTO lyrics VALUES ('local:42', 'la'), ('dQw4w9WgXcQ', 'lb')")
            conn.execSQL("INSERT INTO playCount (song) VALUES ('local:42'), ('dQw4w9WgXcQ')")
            conn.execSQL("INSERT INTO set_video_id VALUES ('local:42'), ('dQw4w9WgXcQ')")
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun count(sql: String): Long =
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { conn ->
            conn.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
        }

    @Test
    fun `scrubbing removes every local-song row and its references and keeps the YouTube rows`() {
        assertTrue(LocalRowScrubber.scrubFile(db))
        for (table in listOf("event", "playlist_song_map", "song_artist_map", "song_album_map", "format", "lyrics", "playCount", "set_video_id")) {
            assertEquals("$table still names a local song", 0L, count("SELECT COUNT(*) FROM $table WHERE ${localColumn(table)} LIKE 'local:%'"))
            assertEquals("$table lost its YouTube row", 1L, count("SELECT COUNT(*) FROM $table WHERE ${localColumn(table)} = 'dQw4w9WgXcQ'"))
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM song WHERE id LIKE 'local:%'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM song WHERE id = 'dQw4w9WgXcQ'"))
        assertEquals(0L, count("SELECT COUNT(*) FROM related_song_map WHERE songId LIKE 'local:%' OR relatedSongId LIKE 'local:%'"))
    }

    @Test
    fun `the scrubbed file no longer holds the bytes of a removed local title`() {
        assertTrue(LocalRowScrubber.scrubFile(db))
        val wal = File("${db.path}-wal")
        val bytes = db.readBytes() + (if (wal.exists()) wal.readBytes() else ByteArray(0))
        assertFalse("the local title is still in the file", String(bytes, Charsets.ISO_8859_1).contains(secretTitle))
    }

    @Test
    fun `a restored file from an older schema without some tables still scrubs`() {
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TABLE set_video_id") }
        assertTrue(LocalRowScrubber.scrubFile(db))
        assertEquals(0L, count("SELECT COUNT(*) FROM song WHERE id LIKE 'local:%'"))
    }

    @Test
    fun `a missing or unreadable file fails closed`() {
        assertFalse(LocalRowScrubber.scrubFile(File(dir, "missing.db")))
    }

    private fun localColumn(table: String) =
        when (table) {
            "format", "lyrics" -> "id"
            "playCount" -> "song"
            "set_video_id" -> "videoId"
            else -> "songId"
        }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the restored-database hardening (audit
// A10-5 — only PRAGMA user_version was checked before swapping in a
// restored song.db; no integrity check, no trigger/view check).
package com.metrolist.music.saraswati.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RestoredDbValidatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Builds a real file-backed Room database so there's an actual sqlite file to open. */
    private fun buildLiveDb(name: String): File {
        val database =
            Room.databaseBuilder(context, InternalDatabase::class.java, name)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .build()
        // Force schema creation (Room creates tables/views lazily on first access).
        database.openHelper.writableDatabase
        database.close()
        return context.getDatabasePath(name)
    }

    private fun liveObjectsOf(dbFile: File): Set<Pair<String, String>> =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            RestoredDbValidator.schemaObjects(db)
        }

    @Test
    fun `accepts a database with the live triggers and views`() {
        val liveFile = buildLiveDb("live_ok.db")
        val liveObjects = liveObjectsOf(liveFile)

        // Room's @DatabaseView entities (SortedSongArtistMap, SortedSongAlbumMap,
        // PlaylistSongMapPreview) should have produced real sqlite views.
        assertTrue("expected the live schema to define at least one view", liveObjects.isNotEmpty())

        val restoredFile = File(liveFile.parentFile, "restored_ok.db")
        liveFile.copyTo(restoredFile, overwrite = true)

        val result = RestoredDbValidator.validate(restoredFile, liveObjects)
        assertEquals(RestoredDbValidator.Result.Ok, result)
    }

    @Test
    fun `rejects an extra trigger`() {
        val liveFile = buildLiveDb("live_trigger.db")
        val liveObjects = liveObjectsOf(liveFile)

        val restoredFile = File(liveFile.parentFile, "restored_trigger.db")
        liveFile.copyTo(restoredFile, overwrite = true)
        SQLiteDatabase.openDatabase(restoredFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE evil_table(id INTEGER)")
            db.execSQL("CREATE TRIGGER evil_trigger AFTER INSERT ON evil_table BEGIN SELECT 1; END")
        }

        val result = RestoredDbValidator.validate(restoredFile, liveObjects)
        assertTrue(result is RestoredDbValidator.Result.Rejected)
    }

    @Test
    fun `rejects an extra view`() {
        val liveFile = buildLiveDb("live_view.db")
        val liveObjects = liveObjectsOf(liveFile)

        val restoredFile = File(liveFile.parentFile, "restored_view.db")
        liveFile.copyTo(restoredFile, overwrite = true)
        SQLiteDatabase.openDatabase(restoredFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE VIEW evil_view AS SELECT 1 AS x")
        }

        val result = RestoredDbValidator.validate(restoredFile, liveObjects)
        assertTrue(result is RestoredDbValidator.Result.Rejected)
    }

    @Test
    fun `rejects a corrupt file`() {
        val corruptFile = File(context.getDatabasePath("live_corrupt.db").parentFile, "corrupt.db")
        corruptFile.parentFile?.mkdirs()
        corruptFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val result = RestoredDbValidator.validate(corruptFile, emptySet())
        assertTrue(result is RestoredDbValidator.Result.Rejected)
    }

    @Test
    fun `rejects a missing file`() {
        val missing = File(context.getDatabasePath("does_not_exist.db").path)
        val result = RestoredDbValidator.validate(missing, emptySet())
        assertTrue(result is RestoredDbValidator.Result.Rejected)
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: audit A10-5 — before this, a restored song.db only had its
// user_version checked (still done by the caller; this adds to it, not
// instead of it). A file with the right version but a corrupt page, or an
// extra trigger/view a hostile backup planted, would otherwise swap
// straight into the live database path. This opens the restored file
// read-only and rejects it unless PRAGMA quick_check passes and its
// trigger/view set is no larger than what Room itself would create — so a
// restored file can be missing triggers/views (an older schema) but cannot
// add ones the live app never defined.
package com.metrolist.music.saraswati.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File

object RestoredDbValidator {
    sealed interface Result {
        object Ok : Result

        data class Rejected(
            val reason: String,
        ) : Result
    }

    /** Every `(type, name)` pair of `trigger`/`view` objects Room (or a legacy schema) defined. */
    fun schemaObjects(db: SQLiteDatabase): Set<Pair<String, String>> {
        val objects = mutableSetOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT type, name FROM sqlite_master WHERE type IN ('trigger', 'view')",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                objects += cursor.getString(0) to cursor.getString(1)
            }
        }
        return objects
    }

    /**
     * Opens [restored] read-only and rejects it unless `PRAGMA quick_check` returns exactly
     * `ok` and its trigger/view pairs ([schemaObjects]) are a subset of [liveObjects]. An
     * unreadable or corrupt file is [Result.Rejected], never a thrown exception.
     */
    fun validate(
        restored: File,
        liveObjects: Set<Pair<String, String>>,
    ): Result {
        if (!restored.exists()) {
            return Result.Rejected("restored database file does not exist")
        }

        val db =
            try {
                SQLiteDatabase.openDatabase(restored.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                return Result.Rejected("could not open restored database: ${e.message}")
            }

        return try {
            val quickCheck =
                db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            if (quickCheck != "ok") {
                return Result.Rejected("PRAGMA quick_check failed: $quickCheck")
            }

            val restoredObjects = schemaObjects(db)
            if (!liveObjects.containsAll(restoredObjects)) {
                val unexpected = restoredObjects - liveObjects
                return Result.Rejected("unexpected trigger/view objects not in the live schema: $unexpected")
            }

            Result.Ok
        } catch (e: Exception) {
            Result.Rejected("validation failed: ${e.message}")
        } finally {
            db.close()
        }
    }
}

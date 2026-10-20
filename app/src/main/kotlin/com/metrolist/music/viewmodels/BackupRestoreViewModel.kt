/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.metrolist.music.R
import okio.Buffer
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.tryOrNull
import com.metrolist.music.extensions.zipInputStream
import com.metrolist.music.extensions.zipOutputStream
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.MusicService.Companion.PERSISTENT_AUTOMIX_FILE
import com.metrolist.music.playback.MusicService.Companion.PERSISTENT_PLAYER_STATE_FILE
import com.metrolist.music.playback.MusicService.Companion.PERSISTENT_QUEUE_FILE
import com.metrolist.music.saraswati.backup.BackupPolicy
import com.metrolist.music.saraswati.backup.BackupTooLargeException
import com.metrolist.music.saraswati.backup.LocalRowScrubber
import com.metrolist.music.saraswati.backup.RestoredDbValidator
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.ArtistNameAliases
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import javax.inject.Inject

data class BackupPreviewInfo(
    val hasAuthData: Boolean = false,
    val accountName: String? = null,
    val accountEmail: String? = null,
    val accountImageUrl: String? = null,
    val cookie: String? = null,
)

data class CsvImportState(
    val previewRows: List<List<String>> = emptyList(),
    val artistColumnIndex: Int = 0,
    val titleColumnIndex: Int = 1,
    val urlColumnIndex: Int = -1,
    val hasHeader: Boolean = true,
)

data class ConvertedSongLog(
    val title: String,
    val artists: String,
)

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {
    fun backup(context: Context, uri: Uri) {
        runCatching {
            context.applicationContext.contentResolver.openOutputStream(uri)?.use {
                it.buffered().zipOutputStream().use { outputStream ->
                    // SARASWATI: audit A6.3/A10-3 — the settings entry used to be a raw
                    // copy of the live preferences file, so a shared/cloud-synced backup
                    // carried the plaintext proxy password, the AI/Last.fm credentials and
                    // the signed-in account's name/email/visitor id in the clear (the cookie
                    // itself is already Keystore-sealed by Task 7, but everything else
                    // wasn't). BackupPolicy.sanitize() drops every key in
                    // NEVER_EXPORT_OR_RESTORE before it's ever serialized into the ZIP.
                    val sanitizedSettings = BackupPolicy.sanitize(runBlocking(Dispatchers.IO) { context.dataStore.data.first() })
                    val sanitizedBytes = serializePreferences(sanitizedSettings)
                    outputStream.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                    outputStream.write(sanitizedBytes)

                    outputStream.putNextEntry(ZipEntry(ArtistNameAliases.BACKUP_FILENAME))
                    outputStream.write(ArtistNameAliases.serialize().encodeToByteArray())
                    runBlocking(Dispatchers.IO) {
                        database.checkpoint()
                    }
                    val dbPath = database.openHelper.writableDatabase.path
                    if (dbPath != null) {
                        // SARASWATI: fix pass 2 (O-2) — the database is exported as a scrubbed copy, never the live
                        // file: local-song rows (`local:<id>`) name files on this phone and must not leave it. The copy
                        // keeps the WAL so it holds every committed row, then LocalRowScrubber rewrites it without them.
                        val scratch = File(context.cacheDir, "backup-export-${System.nanoTime()}").apply { mkdirs() }
                        try {
                            val copy = File(scratch, InternalDatabase.DB_NAME)
                            File(dbPath).copyTo(copy)
                            File("$dbPath-wal").takeIf { it.exists() }?.copyTo(File("${copy.path}-wal"))
                            check(LocalRowScrubber.scrubFile(copy)) { "could not remove local-song rows from the export copy" }
                            FileInputStream(copy).use { inputStream ->
                                outputStream.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                                inputStream.copyTo(outputStream)
                            }
                        } finally {
                            scratch.deleteRecursively()
                        }
                    }
                }
            }
        }.onSuccess {
            Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
        }.onFailure {
            reportException(it)
            Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // SARASWATI: the restore no longer takes a clearAuthData flag (audit A10-2). Every
    // restored settings entry is parsed, then unconditionally run through
    // BackupPolicy.sanitize() before it touches the live DataStore — the cookie, proxy,
    // AI/server URLs and account-identity keys never survive a restore, legacy backup or
    // hostile file alike, so there is no longer a "keep auth data" branch to opt out of.
    fun restore(context: Context, uri: Uri) {
        // Run in viewModelScope to allow suspending
        viewModelScope.launch(Dispatchers.IO) {
            val restoreDbName = "restored_${InternalDatabase.DB_NAME}"
            val restoreDbPath = context.getDatabasePath(restoreDbName).absolutePath

            runCatching {
                Timber.tag("RESTORE").i("Starting restore from URI: $uri")

                val currentDbPath = database.openHelper.writableDatabase.path
                if (currentDbPath == null) {
                    Timber.tag("RESTORE").e("Database path is null, cannot restore")
                    return@launch
                }

                File(restoreDbPath).delete()
                File("$restoreDbPath-wal").delete()
                File("$restoreDbPath-shm").delete()

                var foundDb = false
                var foundSettings = false
                var restoredArtistNameAliases: Map<String, String>? = null
                // SARASWATI: parsed via PreferencesSerializer.readFrom below, so a
                // malformed settings entry throws here — before the DB swap section
                // further down ever runs — and the whole restore aborts via the
                // runCatching/onFailure at the bottom of this function without having
                // replaced anything (audit A10-6).
                var sanitizedSettings: Preferences? = null

                context.applicationContext.contentResolver.openInputStream(uri)?.use { raw ->
                    raw.zipInputStream().use { inputStream ->
                        var entry = tryOrNull { inputStream.nextEntry }
                        while (entry != null) {
                            Timber.tag("RESTORE").i("Found zip entry: ${entry.name}")
                            when (entry.name) {
                                SETTINGS_FILENAME -> {
                                    foundSettings = true
                                    val bytes = BackupPolicy.readLimited(inputStream, BackupPolicy.MAX_SETTINGS_BYTES)
                                    val parsed = deserializePreferences(bytes)
                                    sanitizedSettings = BackupPolicy.sanitize(parsed)
                                }
                                ArtistNameAliases.BACKUP_FILENAME -> {
                                    val bytes = BackupPolicy.readLimited(inputStream, BackupPolicy.MAX_ALIASES_BYTES)
                                    restoredArtistNameAliases = ArtistNameAliases.deserialize(bytes.decodeToString())
                                }
                                InternalDatabase.DB_NAME -> {
                                    foundDb = true
                                    FileOutputStream(restoreDbPath).use { out ->
                                        copyLimited(inputStream, out, BackupPolicy.MAX_DB_BYTES)
                                    }
                                }
                                "${InternalDatabase.DB_NAME}-wal" -> {
                                    // Skip WAL — we'll open cleanly
                                }
                                "${InternalDatabase.DB_NAME}-shm" -> {
                                    // Skip SHM — we'll open cleanly
                                }
                                else -> Timber.tag("RESTORE").i("Skipping unexpected entry: ${entry.name}")
                            }
                            entry = tryOrNull { inputStream.nextEntry }
                        }
                    }
                } ?: run {
                    Timber.tag("RESTORE").e("Could not open input stream for uri: $uri")
                    return@launch
                }

                if (!foundDb && !foundSettings) {
                    Timber.tag("RESTORE").w("No expected entries found in archive")
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                var backupDbVersion = -1
                var currentDbVersion = -1
                if (foundDb) {
                    // Read backup DB version using raw SQLite (no Room involvement)
                    backupDbVersion = InternalDatabase.readDatabaseVersion(restoreDbPath)
                    if (backupDbVersion <= 0) {
                        Timber.tag("RESTORE").e("Cannot read backup database version")
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.restore_database_incompatible, Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    // Read current database version dynamically — this matches whatever Room
                    // annotation says, even when the schema version changes in future builds.
                    currentDbVersion = database.openHelper.writableDatabase.version
                    if (backupDbVersion > currentDbVersion) {
                        Timber.tag("RESTORE").w(
                            "Backup DB version $backupDbVersion > current $currentDbVersion, incompatible"
                        )
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.restore_database_incompatible, Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    // SARASWATI: audit A10-5 — PRAGMA quick_check plus a trigger/view
                    // allowlist against the live app's own schema, on top of the
                    // user_version check above. The restored file is read-only here; it
                    // never gets near the live database path unless this passes. Fails
                    // closed: if the live schema itself can't be read for comparison, the
                    // restore is rejected rather than silently skipping the check.
                    val liveObjects =
                        tryOrNull {
                            android.database.sqlite.SQLiteDatabase.openDatabase(
                                currentDbPath,
                                null,
                                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                            ).use { RestoredDbValidator.schemaObjects(it) }
                        }
                    val validation =
                        liveObjects?.let { RestoredDbValidator.validate(File(restoreDbPath), it) }
                            ?: RestoredDbValidator.Result.Rejected("could not read the live schema to validate against")
                    if (validation is RestoredDbValidator.Result.Rejected) {
                        Timber.tag("RESTORE").e("Restored database rejected: ${validation.reason}")
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                }

                // SARASWATI: fix pass 2 (O-2) — a backup from another phone can carry `local:<n>` rows that name this
                // phone's unrelated files. They are dropped from the restored file before it can replace the live one.
                if (foundDb && !LocalRowScrubber.scrubFile(File(restoreDbPath))) {
                    Timber.tag("RESTORE").e("Could not remove local-song rows from the restored database")
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // === Proceed with restore ===

                // 1. Stop service first — ensures no pending DB writes during swap
                context.stopService(Intent(context, MusicService::class.java))
                try {
                    kotlinx.coroutines.withTimeout(5000) {
                        MusicService.shutdownDeferred.await()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Timeout waiting for MusicService to shutdown")
                }

                // 2. Close the database — all operations should be done by now
                database.close()

                // 3. Swap DB files — staged copy to avoid corrupting the live DB
                var dbSwapSucceeded = true
                if (foundDb) {
                    try {
                        val currentFile = File(currentDbPath)
                        val stagedFile = File("$currentDbPath.restore_staged")
                        val backupFile = File("$currentDbPath.restore_backup")

                        stagedFile.delete()
                        backupFile.delete()

                        File(restoreDbPath).copyTo(stagedFile, overwrite = true)

                        // Preserve current DB, promote staged, remove backup
                        if (!currentFile.renameTo(backupFile)) {
                            error("Failed to preserve current DB before restore")
                        }
                        if (!stagedFile.renameTo(currentFile)) {
                            // Rollback: restore the original
                            backupFile.renameTo(currentFile)
                            error("Failed to promote restored DB")
                        }
                        backupFile.delete()

                        // Delete stale WAL/SHM so the DB opens clean
                        File("$currentDbPath-wal").delete()
                        File("$currentDbPath-shm").delete()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to swap DB files")
                        dbSwapSucceeded = false
                    }
                }

                if (dbSwapSucceeded) {
                    // SARASWATI: audit A10-2 — write the sanitized settings into the live
                    // DataStore via edit {} instead of replacing settings.preferences_pb on
                    // disk. sanitize() has already dropped the cookie/proxy/server/AI/
                    // account-identity keys from `restored`, so this can never plant one of
                    // them. I1: that also means a plain `prefs.clear(); prefs += restored`
                    // would WIPE those same live keys (the sealed cookie, VisitorData,
                    // DataSyncId, account name/email/handle) instead of leaving them alone —
                    // BackupPolicy.mergeRestoreOntoLive snapshots `prefs` (the current live
                    // preferences, which `edit {}` hands this transform before any change)
                    // and writes those exact live values back in on top of the restored
                    // settings, so the restore truly never sets or clears the cookie.
                    if (foundSettings) {
                        sanitizedSettings?.let { restored ->
                            context.dataStore.edit { prefs ->
                                val merged = BackupPolicy.mergeRestoreOntoLive(live = prefs.toPreferences(), restored = restored)
                                prefs.clear()
                                prefs += merged
                            }
                        }
                    }

                    context.filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
                    context.filesDir.resolve(PERSISTENT_AUTOMIX_FILE).delete()
                    context.filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).delete()
                    ArtistNameAliases.restore(context, restoredArtistNameAliases.orEmpty())

                    // 4. Restart — Room will open the swapped DB and run migrations if needed
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    context.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                } else {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure {
                reportException(it)
                Timber.tag("RESTORE").e(it, "Restore failed unexpectedly")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show()
                }
            }

            File(restoreDbPath).delete()
            File("$restoreDbPath-wal").delete()
            File("$restoreDbPath-shm").delete()
        }
    }

    // SARASWATI: audit A10-4/A10-9 — reads the entry through BackupPolicy.readLimited and
    // parses it with the real PreferencesSerializer instead of regex-scanning raw proto
    // bytes for cookie-shaped substrings. The practical effect: a backup this app itself
    // exported now has no InnerTubeCookieKey entry at all (BackupPolicy.sanitize strips it
    // at export time, see backup() above), so hasAuthData is correctly false and the
    // cookie-to-music.youtube.com account-name lookup below never fires for it. Only a
    // legacy, pre-fork Metrolist backup (plaintext cookie) still has auth data to preview.
    // Call sites now run this on Dispatchers.IO (BackupAndRestore.kt), not the main thread.
    fun previewBackup(context: Context, uri: Uri): BackupPreviewInfo {
        return runCatching {
            context.applicationContext.contentResolver.openInputStream(uri)?.use { raw ->
                raw.zipInputStream().use { inputStream ->
                    var entry = tryOrNull { inputStream.nextEntry }
                    while (entry != null) {
                        if (entry.name == SETTINGS_FILENAME) {
                            val bytes = BackupPolicy.readLimited(inputStream, BackupPolicy.MAX_SETTINGS_BYTES)
                            val prefs = deserializePreferences(bytes)

                            val cookie = prefs[InnerTubeCookieKey]
                            val hasAuthData = !cookie.isNullOrEmpty()

                            return BackupPreviewInfo(
                                hasAuthData = hasAuthData,
                                accountName = if (hasAuthData) prefs[AccountNameKey] ?: "YouTube Account" else null,
                                accountEmail = if (hasAuthData) prefs[AccountEmailKey] else null,
                                accountImageUrl = null,
                                cookie = cookie,
                            )
                        }
                        entry = tryOrNull { inputStream.nextEntry }
                    }
                }
            }
            BackupPreviewInfo()
        }.getOrElse {
            Timber.tag("BACKUP_PREVIEW").e(it, "Failed to preview backup")
            BackupPreviewInfo()
        }
    }

    // SARASWATI: audit A10-4 — bounded via BackupPolicy.readLimited instead of an
    // unbounded readLines(). The call site (BackupAndRestore.kt) now runs this on
    // Dispatchers.IO instead of the main thread.
    fun previewCsvFile(context: Context, uri: Uri): CsvImportState {
        val previewRows = mutableListOf<List<String>>()
        val csvState: CsvImportState
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = BackupPolicy.readLimited(stream, BackupPolicy.MAX_IMPORT_TEXT_BYTES)
                val lines = bytes.decodeToString().lineSequence().toList()
                val rowsToPreview = lines.take(6).map { parseCsvLine(it) }
                previewRows.addAll(rowsToPreview)

                val hasHeader = lines.isNotEmpty() && lines[0].contains(",")
                csvState = CsvImportState(
                    previewRows = previewRows,
                    hasHeader = hasHeader,
                )
                return csvState
            }
        }.onFailure {
            reportException(it)
            Toast.makeText(context, "Failed to preview CSV file", Toast.LENGTH_SHORT).show()
        }
        return CsvImportState()
    }

    suspend fun importPlaylistFromCsv(
        context: Context,
        uri: Uri,
        columnMapping: CsvImportState,
        onProgress: (Int) -> Unit = {},
        onLogUpdate: (List<ConvertedSongLog>) -> Unit = {},
    ): ArrayList<Song> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val songs = arrayListOf<Song>()
        val recentLogs = mutableListOf<ConvertedSongLog>()

        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // SARASWATI: audit A10-4 — bounded via BackupPolicy.readLimited instead of
                // an unbounded readLines().
                val bytes = BackupPolicy.readLimited(stream, BackupPolicy.MAX_IMPORT_TEXT_BYTES)
                val lines = bytes.decodeToString().lineSequence().toList()
                val startIndex = if (columnMapping.hasHeader) 1 else 0
                val totalLines = lines.size - startIndex

                lines.drop(startIndex).forEachIndexed { index, line ->
                    val parts = parseCsvLine(line)

                    if (parts.isNotEmpty()) {
                        if (columnMapping.artistColumnIndex < parts.size && columnMapping.titleColumnIndex < parts.size) {
                            val title = parts[columnMapping.titleColumnIndex].trim()
                            val artistStr = parts[columnMapping.artistColumnIndex].trim()

                            if (title.isNotEmpty() && artistStr.isNotEmpty()) {
                                val artists = artistStr.split(";", ",").map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .map { ArtistEntity(id = "", name = it) }

                                val mockSong = Song(
                                    song = SongEntity(
                                        id = "",
                                        title = title,
                                    ),
                                    artists = artists,
                                )
                                songs.add(mockSong)

                                val logEntry = ConvertedSongLog(
                                    title = title,
                                    artists = artists.joinToString(", ") { it.name },
                                )
                                recentLogs.add(0, logEntry)
                                if (recentLogs.size > 3) {
                                    recentLogs.removeAt(recentLogs.size - 1)
                                }
                                onLogUpdate(recentLogs.toList())
                            }
                        }
                    }

                    val progress = ((index + 1) * 100) / totalLines
                    onProgress(progress)
                }
            }
        }.onFailure {
            reportException(it)
        }

        songs
    }

    suspend fun importPlaylistFromCsv(context: Context, uri: Uri): ArrayList<Song> {
        return importPlaylistFromCsv(context, uri, CsvImportState())
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        result.add(current.toString())
        return result.map { it.trim().trim('"') }
    }

    // SARASWATI: audit A10-4 — bounded via BackupPolicy.readLimited instead of an
    // unbounded readLines(). The call site (BackupAndRestore.kt) now runs this on
    // Dispatchers.IO instead of the main thread.
    fun loadM3UOnline(
        context: Context,
        uri: Uri,
    ): ArrayList<Song> {
        val songs = ArrayList<Song>()

        runCatching {
            context.applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = BackupPolicy.readLimited(stream, BackupPolicy.MAX_IMPORT_TEXT_BYTES)
                val lines = bytes.decodeToString().lineSequence().toList()
                if (lines.isNotEmpty() && lines.first().startsWith("#EXTM3U")) {
                    lines.forEachIndexed { _, rawLine ->
                        if (rawLine.startsWith("#EXTINF:")) {
                            val artists =
                                rawLine.substringAfter("#EXTINF:").substringAfter(',').substringBefore(" - ").split(';')
                            val title = rawLine.substringAfter("#EXTINF:").substringAfter(',').substringAfter(" - ")

                            val mockSong = Song(
                                song = SongEntity(
                                    id = "",
                                    title = title,
                                ),
                                artists = artists.map { ArtistEntity("", it) },
                            )
                            songs.add(mockSong)
                        }
                    }
                }
            }
        }

        if (songs.isEmpty()) {
            Toast.makeText(
                context,
                "No songs found. Invalid file, or perhaps no song matches were found.",
                Toast.LENGTH_SHORT
            ).show()
        }
        return songs
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
    }
}

// SARASWATI: shared helpers so both export (backup()) and restore
// (restore()/previewBackup()) go through the exact same proto encoding —
// androidx.datastore.preferences.core.PreferencesSerializer — instead of
// hand-rolled byte copying or regex scanning.
private fun serializePreferences(prefs: Preferences): ByteArray {
    val buffer = Buffer()
    runBlocking { PreferencesSerializer.writeTo(prefs, buffer) }
    return buffer.readByteArray()
}

private fun deserializePreferences(bytes: ByteArray): Preferences =
    runBlocking { PreferencesSerializer.readFrom(Buffer().write(bytes)) }

// SARASWATI: audit A10-4 — streams the database entry straight to [output] instead of
// buffering it in memory, aborting (and deleting nothing extra; the caller's existing
// cleanup handles the partial file) the instant more than [maxBytes] would be written.
private fun copyLimited(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
) {
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        val remaining = maxBytes + 1 - total
        if (remaining <= 0) throw BackupTooLargeException()
        val toRead = minOf(chunk.size.toLong(), remaining).toInt()
        val n = input.read(chunk, 0, toRead)
        if (n < 0) break
        output.write(chunk, 0, n)
        total += n
        if (total > maxBytes) throw BackupTooLargeException()
    }
}

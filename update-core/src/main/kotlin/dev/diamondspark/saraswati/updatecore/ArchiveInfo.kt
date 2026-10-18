/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — this module has no Android dependency, so it cannot
// itself read an APK's package name, versionCode or signing certificates
// (that needs android.content.pm.PackageManager or apksigner). The
// Saraswati Updates installer app (Task 5) supplies the real
// implementation; this module only defines the contract and, in its own
// tests, a fake.
package dev.diamondspark.saraswati.updatecore

import java.io.File

data class ArchiveInfo(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

fun interface ArchiveInspector {
    /** Returns null when [file] cannot be read as a valid, parseable APK/ZIP archive. */
    fun inspect(file: File): ArchiveInfo?
}

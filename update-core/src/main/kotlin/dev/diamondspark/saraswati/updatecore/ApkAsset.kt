/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — manifest schema (see ManifestParser for the rules
// that make an instance of this trustworthy, not just well-typed).
package dev.diamondspark.saraswati.updatecore

import kotlinx.serialization.Serializable

@Serializable
data class ApkAsset(
    val url: String,
    val sha256: String,
    val size: Long,
)

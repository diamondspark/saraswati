/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 3 — one audio file as MediaStore describes it, before any filtering.
package com.metrolist.music.saraswati.localmedia

data class MediaStoreAudioRow(
    val id: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumId: Long?,
    val durationMs: Long,
    val mimeType: String?,
    val relativePath: String?,
    val displayName: String?,
    val dateModifiedSec: Long,
    val isRecording: Boolean,
)

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 7 — one play as the Prahar query reads it: only non-local songs, only the last 60 days.
package com.metrolist.music.saraswati.craft.prahar

import java.time.LocalDateTime

data class PraharEventRow(
    val songId: String,
    val timestamp: LocalDateTime,
    val playTime: Long,
)

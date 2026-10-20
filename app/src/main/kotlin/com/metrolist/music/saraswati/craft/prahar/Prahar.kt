/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 6 — the eight three-hour windows of the day, on the phone's wall clock. Each window starts on
// its startHour and runs for three hours; the windows cover the day without overlap.
package com.metrolist.music.saraswati.craft.prahar

import java.time.LocalDateTime
import java.time.LocalTime

enum class Prahar(
    val startHour: Int,
    val hindi: String,
    val english: String,
) {
    PRATAH(6, "Pratah", "Morning"),
    PURVAHNA(9, "Purvahna", "Late morning"),
    MADHYAHNA(12, "Madhyahna", "Midday"),
    APARAHNA(15, "Aparahna", "Afternoon"),
    SANDHYA(18, "Sandhya", "Evening"),
    PRADOSH(21, "Pradosh", "Late evening"),
    NISHITH(0, "Nishith", "Midnight"),
    USHA(3, "Usha", "Before dawn"),
    ;

    companion object {
        private const val WINDOW_HOURS = 3

        fun of(time: LocalTime): Prahar =
            values().first { Math.floorMod(time.hour - it.startHour, 24) < WINDOW_HOURS }
    }
}

/** One play of a song, at the wall-clock moment it started. */
data class PraharPlay(
    val songId: String,
    val at: LocalDateTime,
    val playTimeMs: Long,
)

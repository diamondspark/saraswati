/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — "what time is it on that phone right now", from the
// timezone the phone itself reported. The owner is abroad, so a raw
// "3 hours ago" does not say whether it is a decent hour to call.
package com.metrolist.music.saraswati.family

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DeviceLocalTime {
    /** The clock time in [timezone] at [nowMillis], or null when the zone is unknown (never a wrong time). */
    fun format(
        timezone: String,
        nowMillis: Long,
        is24Hour: Boolean,
        locale: Locale,
    ): String? {
        if (timezone.isBlank()) return null
        val zone =
            try {
                ZoneId.of(timezone.trim())
            } catch (e: DateTimeException) {
                return null
            }
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(nowMillis).atZone(zone))
    }
}

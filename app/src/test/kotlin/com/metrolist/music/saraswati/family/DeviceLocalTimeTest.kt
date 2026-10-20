/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the health view shows each phone's own current time,
// computed from the timezone that phone reported, so the owner abroad can
// see at a glance whether it is a reasonable hour to call.
package com.metrolist.music.saraswati.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Locale

class DeviceLocalTimeTest {
    // 2026-10-19 18:12 UTC: 23:42 in India (+05:30), 14:12 in Toronto (EDT, -04:00).
    private val now = Instant.parse("2026-10-19T18:12:00Z").toEpochMilli()

    @Test
    fun `formats the time in the device's own timezone on a 12 hour clock`() {
        assertEquals("11:42 PM", DeviceLocalTime.format("Asia/Kolkata", now, is24Hour = false, Locale.US))
        assertEquals("2:12 PM", DeviceLocalTime.format("America/Toronto", now, is24Hour = false, Locale.US))
    }

    @Test
    fun `formats on a 24 hour clock`() {
        assertEquals("23:42", DeviceLocalTime.format("Asia/Kolkata", now, is24Hour = true, Locale.US))
        assertEquals("14:12", DeviceLocalTime.format("America/Toronto", now, is24Hour = true, Locale.US))
    }

    @Test
    fun `follows daylight saving rules of the reported zone`() {
        val winter = Instant.parse("2026-12-19T18:12:00Z").toEpochMilli()
        assertEquals("1:12 PM", DeviceLocalTime.format("America/Toronto", winter, is24Hour = false, Locale.US))
        assertEquals("11:42 PM", DeviceLocalTime.format("Asia/Kolkata", winter, is24Hour = false, Locale.US))
    }

    @Test
    fun `an offset style zone id works too`() {
        assertEquals("11:42 PM", DeviceLocalTime.format("+05:30", now, is24Hour = false, Locale.US))
    }

    @Test
    fun `an unknown or empty timezone gives no time rather than a wrong one`() {
        assertNull(DeviceLocalTime.format("Mars/Olympus_Mons", now, is24Hour = false, Locale.US))
        assertNull(DeviceLocalTime.format("", now, is24Hour = false, Locale.US))
        assertNull(DeviceLocalTime.format("   ", now, is24Hour = false, Locale.US))
    }
}

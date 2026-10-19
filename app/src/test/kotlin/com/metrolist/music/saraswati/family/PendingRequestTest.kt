/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B1 — the four-character code both phones show for a
// pending request, how old the request is, and when the owner's phone stops
// offering Approve. The hub refuses an approval after an hour (410); the row
// goes grey before that call is made.
package com.metrolist.music.saraswati.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PendingRequestTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_760_000_000_000L

    // --- the code ---------------------------------------------------------

    @Test
    fun `the code is the last four characters of the device id in capitals`() {
        assertEquals("0A3B", ClaimCode.of("3f2b8c1e-9a4d-4f6b-8e2a-1c5d7e9f0a3b"))
        assertEquals("CE-1", ClaimCode.of("device-1"))
    }

    @Test
    fun `a short device id is shown whole, and an empty one gives an empty code`() {
        assertEquals("AB", ClaimCode.of("ab"))
        assertEquals("", ClaimCode.of(""))
    }

    @Test
    fun `the code does not depend on the phone's language`() {
        val before = Locale.getDefault()
        try {
            // In Turkish, a plain uppercase() turns "i" into a dotted capital I.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("ICIK", ClaimCode.of("xxxxicik"))
        } finally {
            Locale.setDefault(before)
        }
    }

    // --- the age ----------------------------------------------------------

    @Test
    fun `an age under a minute is just now, and a negative one (clock skew) too`() {
        assertEquals(RequestAge.JustNow, RequestAge.of(0))
        assertEquals(RequestAge.JustNow, RequestAge.of(59_999))
        assertEquals(RequestAge.JustNow, RequestAge.of(-5 * minute))
    }

    @Test
    fun `an age is whole minutes up to an hour`() {
        assertEquals(RequestAge.Minutes(1), RequestAge.of(minute))
        assertEquals(RequestAge.Minutes(2), RequestAge.of(2 * minute + 30_000))
        assertEquals(RequestAge.Minutes(59), RequestAge.of(hour - 1))
    }

    @Test
    fun `an age of an hour or more is whole hours`() {
        assertEquals(RequestAge.Hours(1), RequestAge.of(hour))
        assertEquals(RequestAge.Hours(1), RequestAge.of(hour + 59 * minute))
        assertEquals(RequestAge.Hours(5), RequestAge.of(5 * hour + 3 * minute))
    }

    // --- the row ----------------------------------------------------------

    private fun entry(claimedAt: Long?) = RosterEntry("3f2b8c1e-9a4d-4f6b-8e2a-1c5d7e9f0a3b", "Mummy's Pixel", "pending", null, DeviceRole.ADULT, claimedAt)

    @Test
    fun `a fresh request shows its code and age and can be approved`() {
        val ui = entry(now - 2 * minute).pendingRequest(now)

        assertEquals("0A3B", ui.code)
        assertEquals(RequestAge.Minutes(2), ui.age)
        assertFalse(ui.expired)
        assertTrue(ui.canApprove)
    }

    @Test
    fun `a request exactly an hour old can still be approved, one millisecond older cannot`() {
        // The hub's rule: refused when now - claimedAt is more than an hour.
        assertTrue(entry(now - hour).pendingRequest(now).canApprove)

        val stale = entry(now - hour - 1).pendingRequest(now)
        assertTrue(stale.expired)
        assertFalse(stale.canApprove)
    }

    @Test
    fun `an expired request still shows its code and a real age`() {
        val ui = entry(now - 3 * hour).pendingRequest(now)

        assertTrue(ui.expired)
        assertEquals("0A3B", ui.code)
        assertEquals(RequestAge.Hours(3), ui.age)
    }

    @Test
    fun `a request with no claim time has no age and is left for the hub to judge`() {
        val ui = entry(null).pendingRequest(now)

        assertNull(ui.age)
        assertFalse(ui.expired)
        assertTrue(ui.canApprove)
    }

    @Test
    fun `the expiry window is the hub's one hour`() {
        assertEquals(hour, PENDING_APPROVAL_TTL_MS)
    }
}

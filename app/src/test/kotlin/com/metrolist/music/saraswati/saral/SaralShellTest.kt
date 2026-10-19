/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — what MainActivity hosts and routes for each cached
// role. A full MainActivity cannot start in a plain JVM test (Hilt, a bound
// service, a Compose host), so the decision itself is a pure function and
// SaralIsolationTest pins that MainActivity and the Saral screen actually
// obey it.
package com.metrolist.music.saraswati.saral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaralShellTest {
    @Test
    fun `a grandparent phone hosts Saral and nothing else`() {
        assertEquals(SaralShell.Content.SARAL, SaralShell.contentFor(grandparent = true))
    }

    @Test
    fun `any other phone hosts the whole app`() {
        assertEquals(SaralShell.Content.FULL_APP, SaralShell.contentFor(grandparent = false))
    }

    @Test
    fun `while Saral is active no link, widget, share or recognition intent is routed`() {
        assertFalse(SaralShell.routesIncomingIntents(grandparent = true))
    }

    @Test
    fun `an adult phone routes intents as before`() {
        assertTrue(SaralShell.routesIncomingIntents(grandparent = false))
    }
}

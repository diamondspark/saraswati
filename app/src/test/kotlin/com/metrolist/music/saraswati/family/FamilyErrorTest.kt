/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B1 — how a hub status becomes a FamilyError. 410 means two different things:
// a pairing link already spent (the claiming phone asks for a new link) and an approval the hub
// refuses after an hour (the owner asks the person to send a new request).
package com.metrolist.music.saraswati.family

import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyErrorTest {
    @Test
    fun `a 410 on a claim is the link being gone, as before`() {
        assertEquals(FamilyError.GONE, FamilyError.from(hubFailure(410)))
    }

    @Test
    fun `a 410 on an approval is the request having expired`() {
        assertEquals(FamilyError.EXPIRED, FamilyError.fromApproval(hubFailure(410)))
    }

    @Test
    fun `every other approval failure maps exactly as it does elsewhere`() {
        for (status in listOf(400, 401, 404, 413, 429, 500, 503)) {
            assertEquals("HTTP $status", FamilyError.from(hubFailure(status)), FamilyError.fromApproval(hubFailure(status)))
        }
        assertEquals(FamilyError.OFFLINE, FamilyError.fromApproval(OFFLINE))
    }

    @Test
    fun `a body the hub finds too large is the person's input being unusable`() {
        assertEquals(FamilyError.INVALID_INPUT, FamilyError.from(hubFailure(413)))
    }

    @Test
    fun `a revoked bearer is unauthorized`() {
        assertEquals(FamilyError.UNAUTHORIZED, FamilyError.from(hubFailure(401)))
    }
}

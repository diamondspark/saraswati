/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 16 — the relay session hands out the token and the opaque ids.
package com.metrolist.music.saraswati.castrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class RelaySessionTest {
    private val random = SecureRandom()

    @Test
    fun `token is 32 random bytes as hex`() {
        val token = RelaySession(random).token
        assertEquals(64, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `opaque ids differ from video ids and between sessions`() {
        val first = RelaySession(random).register(listOf("dQw4w9WgXcQ"))
        val second = RelaySession(random).register(listOf("dQw4w9WgXcQ"))
        val opaque = first.getValue("dQw4w9WgXcQ")
        assertEquals(32, opaque.length)
        assertNotEquals("dQw4w9WgXcQ", opaque)
        assertNotEquals(opaque, second.getValue("dQw4w9WgXcQ"))
    }

    @Test
    fun `register keeps the same opaque id for a video the session already issued`() {
        val session = RelaySession(random)
        val first = session.register(listOf("a1")).getValue("a1")
        val again = session.register(listOf("a1", "b2")).getValue("a1")
        assertEquals(first, again)
        assertEquals("a1", session.resolve(first))
    }

    @Test
    fun `resolve returns the video id and null for an unknown id`() {
        val session = RelaySession(random)
        val opaque = session.register(listOf("vid")).getValue("vid")
        assertEquals("vid", session.resolve(opaque))
        assertNull(session.resolve("vid"))
        assertNull(session.resolve("0".repeat(32)))
    }
}

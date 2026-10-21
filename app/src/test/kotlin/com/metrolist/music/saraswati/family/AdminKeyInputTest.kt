/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: a pasted admin key must reach the hub as the exact characters the owner copied. Every key
// here is a made-up fixture, never the owner's.
package com.metrolist.music.saraswati.family

import org.junit.Assert.assertEquals
import org.junit.Test

class AdminKeyInputTest {
    private val key = "a".repeat(32) + "0123456789abcdef".repeat(2)

    @Test
    fun `a key of visible characters is returned unchanged`() {
        assertEquals(key, AdminKeyInput.clean(key))
        assertEquals("Mixed-Case_key.with+symbols/=", AdminKeyInput.clean("Mixed-Case_key.with+symbols/="))
    }

    @Test
    fun `ordinary whitespace at the ends goes`() {
        assertEquals(key, AdminKeyInput.clean("  $key \n"))
        assertEquals(key, AdminKeyInput.clean("\t$key\r\n"))
    }

    @Test
    fun `a line wrapped in the middle is joined`() {
        assertEquals(key, AdminKeyInput.clean(key.take(40) + "\n" + key.drop(40)))
        assertEquals(key, AdminKeyInput.clean(key.take(40) + "\r\n  " + key.drop(40)))
        assertEquals(key, AdminKeyInput.clean(key.chunked(8).joinToString(" ")))
    }

    @Test
    fun `no-break and other Unicode spaces go`() {
        assertEquals(key, AdminKeyInput.clean("\u00A0$key\u00A0"))
        assertEquals(key, AdminKeyInput.clean("\u2003$key\u3000"))
        assertEquals(key, AdminKeyInput.clean("$key\u2028"))
    }

    @Test
    fun `zero-width and other invisible format characters go`() {
        assertEquals(key, AdminKeyInput.clean("\uFEFF$key"))
        assertEquals(key, AdminKeyInput.clean(key.take(10) + "\u200B" + key.drop(10)))
        assertEquals(key, AdminKeyInput.clean(key.take(10) + "\u200C\u200D\u2060" + key.drop(10)))
        assertEquals(key, AdminKeyInput.clean("\u200E$key\u200F"))
        assertEquals(key, AdminKeyInput.clean("\u202A$key\u202C"))
        assertEquals(key, AdminKeyInput.clean(key.take(10) + "\u00AD" + key.drop(10)))
    }

    @Test
    fun `control characters go`() {
        assertEquals(key, AdminKeyInput.clean("\u0000$key\u0007"))
        assertEquals(key, AdminKeyInput.clean("$key\u001B"))
    }

    @Test
    fun `a visible character that is not part of the key is kept, so the hub still refuses it`() {
        assertEquals("\"$key\"", AdminKeyInput.clean("\"$key\""))
        assertEquals("$key=", AdminKeyInput.clean("$key="))
    }

    @Test
    fun `nothing visible leaves nothing`() {
        assertEquals("", AdminKeyInput.clean(""))
        assertEquals("", AdminKeyInput.clean("  \n\t"))
        assertEquals("", AdminKeyInput.clean("\u200B\uFEFF\u00A0"))
    }
}

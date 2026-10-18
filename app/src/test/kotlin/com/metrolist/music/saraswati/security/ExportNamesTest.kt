/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the export-name sanitizer (audit NEW-7).
// A hostile YouTube/local playlist title must never produce a file name
// that escapes PlaylistExporter's export directory.
package com.metrolist.music.saraswati.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportNamesTest {
    @Test
    fun `traversal and separators are replaced`() {
        val result = ExportNames.sanitize("../../../etc/passwd")
        assertFalse(result.contains("/"))
        assertFalse(result.contains("\\"))
        assertFalse(result.contains(".."))
        assertEquals("etc passwd", result)
    }

    @Test
    fun `letters, digits and spaces including Devanagari are kept`() {
        assertEquals("Road Trip 2024", ExportNames.sanitize("Road Trip 2024"))
        assertEquals("संगीत सूची", ExportNames.sanitize("संगीत सूची"))
    }

    @Test
    fun `leading dots are stripped`() {
        assertEquals("hidden", ExportNames.sanitize(".hidden"))
        assertEquals("playlist", ExportNames.sanitize(".."))
        assertEquals("playlist", ExportNames.sanitize("..."))
    }

    @Test
    fun `empty or blank becomes playlist`() {
        assertEquals("playlist", ExportNames.sanitize(""))
        assertEquals("playlist", ExportNames.sanitize("   "))
    }

    @Test
    fun `length is capped at 80`() {
        val result = ExportNames.sanitize("a".repeat(500))
        assertTrue(result.length <= 80)
        assertEquals(80, result.length)
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the PoToken/login WebView's input
// validators (audit A8.1). visitorData/videoId come from server responses
// and must be shape-checked before they are ever spliced into a JS
// snippet passed to evaluateJavascript; jsStringLiteral is the only
// sanctioned way to splice a dynamic value into that JS text at all.
package com.metrolist.music.saraswati.security

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebInputsTest {
    @Test
    fun `accepts a realistic visitor data`() {
        assertTrue(WebInputs.isValidVisitorData("CgtBYmNkZUZnaEppSyiYqPHABjIKCgJJThIEGgAgPQ%3D%3D"))
    }

    @Test
    fun `rejects a visitor data that closes the string and runs script`() {
        assertFalse(WebInputs.isValidVisitorData("x\";PoTokenWebView.onRunBotguardResult(document.cookie)//"))
    }

    @Test
    fun `rejects spaces, empty and over 512 characters`() {
        assertFalse(WebInputs.isValidVisitorData(""))
        assertFalse(WebInputs.isValidVisitorData("has a space"))
        assertFalse(WebInputs.isValidVisitorData("a".repeat(513)))
        assertTrue(WebInputs.isValidVisitorData("a".repeat(512)))
        assertFalse(WebInputs.isValidVisitorData("short"))
    }

    @Test
    fun `accepts a standard video id`() {
        assertTrue(WebInputs.isValidVideoId("dQw4w9WgXcQ"))
        assertTrue(WebInputs.isValidVideoId("a_B-9"))
    }

    @Test
    fun `rejects quotes, slashes and over 64 characters in a video id`() {
        assertFalse(WebInputs.isValidVideoId("dQw4w9\"WgXcQ"))
        assertFalse(WebInputs.isValidVideoId("dQw4w9/WgXcQ"))
        assertFalse(WebInputs.isValidVideoId(""))
        assertFalse(WebInputs.isValidVideoId("a".repeat(65)))
        assertTrue(WebInputs.isValidVideoId("a".repeat(64)))
    }

    @Test
    fun `jsStringLiteral output parses back to the original and has no raw quote, backslash or newline inside`() {
        val input = "line1\nquote\"back\\slash</script>sep end"
        val literal = WebInputs.jsStringLiteral(input)

        assertTrue(literal.startsWith("\""))
        assertTrue(literal.endsWith("\""))
        assertEquals(input, Json.decodeFromString<String>(literal))

        val interior = literal.substring(1, literal.length - 1)
        // Every backslash in the interior must start a recognized escape — walk it manually so a
        // raw, unescaped control character or quote can't hide between two valid escapes.
        var i = 0
        while (i < interior.length) {
            val c = interior[i]
            assertTrue("raw double quote at $i", c != '"')
            assertTrue("raw newline at $i", c != '\n')
            assertTrue("raw U+2028 at $i", c != ' ')
            assertTrue("raw U+2029 at $i", c != ' ')
            if (c == '\\') {
                assertTrue("dangling backslash at end", i + 1 < interior.length)
                i += 2
            } else {
                i += 1
            }
        }
        assertFalse("must not contain a literal </script> sequence", literal.contains("</script>"))
    }
}

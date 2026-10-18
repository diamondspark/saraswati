/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: a playlist title is attacker-controlled data (it comes straight
// from a YouTube Music playlist, local or remote) and PlaylistExporter turns
// it directly into a file name under the app's export directory. A title
// like "../../../etc" or ".." must not be able to escape that directory, and
// a title that is empty/blank after stripping must still produce a usable
// file name (audit NEW-7).
package com.metrolist.music.saraswati.security

object ExportNames {
    private const val MAX_LENGTH = 80
    private const val FALLBACK = "playlist"

    // Letters, combining marks (e.g. Devanagari matras) and digits in any
    // script, plus literal spaces and dots, are kept; everything else
    // (path separators "/" and "\", control characters, etc.) becomes a
    // space so no part of the original title can act as a path segment.
    private val DISALLOWED_CHAR = Regex("[^\\p{L}\\p{M}\\p{Nd} .]")
    private val REPEATED_DOTS = Regex("\\.{2,}")
    private val REPEATED_SPACES = Regex(" {2,}")

    fun sanitize(name: String): String {
        val safeChars = DISALLOWED_CHAR.replace(name, " ")
        val noDoubleDots = REPEATED_DOTS.replace(safeChars, ".")
        val collapsed = REPEATED_SPACES.replace(noDoubleDots, " ").trim()
        val noLeadingDots = collapsed.trimStart('.', ' ')
        return noLeadingDots.ifBlank { FALLBACK }.take(MAX_LENGTH)
    }
}

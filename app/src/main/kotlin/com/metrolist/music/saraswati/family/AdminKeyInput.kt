/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: the owner pastes the admin key (64 hex characters) from another device. A phone
// clipboard can carry more than the key: a line break where the text wrapped, a no-break space, a
// zero-width space or byte-order mark. String.trim() takes only the ends, and only whitespace, so
// such a paste either reaches the hub as a different string or makes OkHttp refuse the header.
package com.metrolist.music.saraswati.family

internal object AdminKeyInput {
    /**
     * The longest cleaned input that is sent to the hub as a key. A real key is 64 characters; unrelated clipboard
     * text (a paragraph, a log) collapses into one long string once its whitespace goes, and must not be sent as a bearer.
     */
    const val MAX_LENGTH = 128

    /**
     * [pasted] without any whitespace (Unicode spaces and line breaks, wherever they sit), control
     * characters or invisible format characters (zero-width space and joiners, bidi marks, BOM,
     * soft hyphen). A key made of visible characters passes through unchanged, so nothing the hub
     * would accept is altered. Never logs or throws, and never returns more than it was given.
     */
    fun clean(pasted: String): String = pasted.filterNot { it.isWhitespace() || it.isISOControl() || it.category == CharCategory.FORMAT }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: shape-checks for values that end up spliced into a JS snippet
// run by the login or PoToken WebView (audit A8.1). visitorData/videoId
// come from a server response or from the page itself, so they are
// validated before use and, win or lose, jsStringLiteral is the only
// sanctioned way to put a dynamic value inside that JS text at all.
package com.metrolist.music.saraswati.security

import kotlinx.serialization.json.JsonPrimitive

object WebInputs {
    private val VISITOR_DATA = Regex("^[A-Za-z0-9_%=-]{8,512}$")
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isValidVisitorData(s: String): Boolean = VISITOR_DATA.matches(s)

    fun isValidVideoId(s: String): Boolean = VIDEO_ID.matches(s)

    /**
     * Renders [s] as a double-quoted JavaScript string literal safe to splice directly into JS
     * source text (e.g. an `evaluateJavascript` snippet).
     *
     * `kotlinx.serialization.json.JsonPrimitive(s).toString()` already produces a valid,
     * correctly-quoted JSON string — it escapes `"`, `\`, and control characters including
     * newline. That alone is not enough once the result lands inside script text rather than a
     * JSON document, so this additionally escapes:
     *  - `</` to `<\/`, so the literal can never contain `</script>` and break out of an
     *    enclosing `<script>` tag if this value is ever loaded via `loadData`/`loadDataWithBaseURL`
     *    instead of `evaluateJavascript`;
     *  - U+2028/U+2029 (LINE SEPARATOR/PARAGRAPH SEPARATOR) to ` `/` `, since these are
     *    JS line terminators that some string-literal grammars (pre-ES2019) reject unescaped,
     *    even though a bare JSON string permits them unescaped.
     *
     * Both additions are themselves valid JSON escapes (`\/` and `\uXXXX` are both in the JSON
     * grammar), so the result still round-trips through `Json.decodeFromString<String>()`.
     */
    fun jsStringLiteral(s: String): String =
        JsonPrimitive(s)
            .toString()
            .replace("</", "<\\/")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")
}

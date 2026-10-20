/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — what the family hub's POST /ask answers, as the phone holds it. The hub is the
// only place a question goes. The phone checks the answer again with the Worker's own limits (worker/src/ask/
// intent.ts), so a reply it does not expect becomes a search for the question, never something it acts on.
// Nothing here logs or keeps the question or the answer.
package com.metrolist.music.saraswati.ask

import com.metrolist.music.saraswati.family.AskWireReply
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import java.time.Instant

sealed interface AskIntent {
    data class Search(
        val query: String,
    ) : AskIntent

    data class PlayStation(
        val stationId: String,
    ) : AskIntent

    data object ParivaarMix : AskIntent

    data object Sangat : AskIntent

    data class Answer(
        val text: String,
    ) : AskIntent
}

/** [fallback] is true when the hub could not decide, or the phone could not read what it sent. */
data class AskReply(
    val intent: AskIntent,
    val fallback: Boolean,
)

/** The family's or the phone's daily allowance is spent until [resetAt]. */
class AskLimitReached(
    val resetAt: Instant,
) : Exception("ask limit reached")

/** The reply checks, in the Worker's words. */
internal object AskWire {
    const val QUERY_MAX = 100
    const val TEXT_MAX = 280
    private const val STATION_ID_MAX = 128

    private val CONTROL = Regex("[\\p{Cc}\\u2028\\u2029]")
    private val BIDI = Regex("[\\u061c\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")
    private val LINK_MARKERS = listOf("://", "www.", "mailto:", "javascript:")

    fun reply(
        wire: AskWireReply,
        question: String,
    ): AskReply {
        val intent = intentFrom(wire.intent) ?: return AskReply(searchFor(question), fallback = true)
        return AskReply(intent, fallback = wire.fallback)
    }

    /** A search for the question as the person said or typed it, with the query limit applied. */
    fun searchFor(question: String): AskIntent.Search {
        var query = question.replace(CONTROL, " ").trim().take(QUERY_MAX)
        if (query.isNotEmpty() && query.last().isHighSurrogate()) query = query.dropLast(1)
        return AskIntent.Search(query.trimEnd())
    }

    private fun intentFrom(obj: JsonObject?): AskIntent? {
        if (obj == null) return null
        return when (text(obj, "kind")) {
            "search" -> clean(text(obj, "query"), QUERY_MAX)?.let { AskIntent.Search(it) }
            "play_station" ->
                text(obj, "stationId")
                    ?.takeIf { it.isNotEmpty() && it.length <= STATION_ID_MAX && !CONTROL.containsMatchIn(it) }
                    ?.let { AskIntent.PlayStation(it) }
            "parivaar_mix" -> AskIntent.ParivaarMix
            "sangat" -> AskIntent.Sangat
            "answer" ->
                clean(text(obj, "text"), TEXT_MAX)
                    ?.takeIf { !BIDI.containsMatchIn(it) && !hasLink(it) }
                    ?.let { AskIntent.Answer(it) }
            else -> null
        }
    }

    /** Trimmed, 1 to [max] characters, no control characters or line separators; else null. */
    private fun clean(
        value: String?,
        max: Int,
    ): String? {
        val text = value?.trim() ?: return null
        return text.takeIf { it.isNotEmpty() && it.length <= max && !CONTROL.containsMatchIn(it) }
    }

    /** NFKC folds full-width letters and dots, so a link written with them is still caught. */
    private fun hasLink(text: String): Boolean {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        return LINK_MARKERS.any { folded.contains(it) }
    }

    private fun text(
        obj: JsonObject,
        key: String,
    ): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

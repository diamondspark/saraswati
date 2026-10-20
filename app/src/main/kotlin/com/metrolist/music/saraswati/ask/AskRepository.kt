/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — one question to the family hub, with the time it may take and nothing more. It never
// retries: a slow, failed or refused ask is Unavailable (or LimitReached), and the caller decides what the person sees.
// No session or the switch off means no call at all.
package com.metrolist.music.saraswati.ask

import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubIdentity
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.util.Locale

const val ASK_TIMEOUT_MS = 6000L

// SARASWATI: Phase 6 fix (C-M3) — the Worker refuses longer questions, so the phone sends no more than this.
const val ASK_QUESTION_MAX = 200

/** The hub's side of one ask; HubClient is the only implementation. */
interface AskHub {
    suspend fun ask(
        sessionToken: String,
        question: String,
        locale: String,
    ): Result<AskReply>
}

sealed interface AskOutcome {
    data class Reply(
        val reply: AskReply,
    ) : AskOutcome

    data class LimitReached(
        val resetAt: Instant,
    ) : AskOutcome

    data object Unavailable : AskOutcome
}

class AskRepository(
    private val hub: AskHub,
    private val identity: HubIdentity,
    private val gate: FamilyFeatureGate,
    private val clock: Clock,
) {
    suspend fun ask(
        question: String,
        timeoutMs: Long = ASK_TIMEOUT_MS,
    ): AskOutcome {
        val text = question.trim().take(ASK_QUESTION_MAX)
        // SARASWATI: Phase 6 fix (C-I1) — the question leaves the phone, so the switch must be known to be on (fail closed).
        if (text.isEmpty() || !gate.allows(FamilyFeature.ASK_SARASWATI)) return AskOutcome.Unavailable
        val token = identity.sessionToken() ?: return AskOutcome.Unavailable
        val answer =
            withTimeoutOrNull(timeoutMs) { hub.ask(token, text, appLocale(Locale.getDefault())) }
                ?: return AskOutcome.Unavailable
        answer.onSuccess { return AskOutcome.Reply(it) }
        val limit = answer.exceptionOrNull() as? AskLimitReached ?: return AskOutcome.Unavailable
        // A reset time that is not in the future cannot be shown as "ask again tomorrow", so it is not a limit.
        return if (limit.resetAt.isAfter(clock.instant())) AskOutcome.LimitReached(limit.resetAt) else AskOutcome.Unavailable
    }
}

/** The phone's language as the Worker takes it: xx or xx-YY; English when the language is not two letters. */
internal fun appLocale(locale: Locale): String {
    val language = locale.language.takeIf { it.length == 2 && it.all(Char::isLowerCase) } ?: "en"
    val country = locale.country.takeIf { it.length == 2 && it.all(Char::isUpperCase) }
    return if (country == null) language else "$language-$country"
}

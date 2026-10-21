/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: feedback — the hub's feedback shapes: what a phone sends, what the owner's inbox reads back.
//
// The report text is untrusted and private, so the wire request redacts it from toString(). A list item the
// app cannot represent (an unknown kind or status, or a missing field) is skipped by HubClient rather than
// failing the whole list, so a hub that grows a status never blanks an older app's inbox.
package com.metrolist.music.saraswati.family.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

enum class FeedbackKind(
    val wire: String,
) {
    Bug("bug"),
    Idea("idea"),
}

enum class FeedbackStatus(
    val wire: String,
) {
    New("new"),
    Seen("seen"),
    Planned("planned"),
    Done("done"),
    WontFix("wontfix"),
}

data class FeedbackDiagnostics(
    val appVersion: String,
    val android: String,
    val device: String,
)

data class FeedbackItem(
    val id: String,
    val kind: FeedbackKind,
    val text: String,
    val status: FeedbackStatus,
    val nickname: String,
    val createdAt: Long,
    val diagnostics: FeedbackDiagnostics?,
    val issueUrl: String?,
)

interface FeedbackHub {
    /** [bearer] is a paired phone's session token, or the owner's admin key. Returns the new report's id. */
    suspend fun submitFeedback(
        bearer: String,
        kind: FeedbackKind,
        text: String,
        diagnostics: FeedbackDiagnostics?,
    ): Result<String>

    /** Owner only. [status] is the hub's filter: open (the default), all, or one status's wire name. */
    suspend fun listFeedback(
        adminKey: String,
        status: String = "open",
    ): Result<List<FeedbackItem>>

    /** Owner only. 404 is an unknown id. */
    suspend fun setFeedbackStatus(
        adminKey: String,
        id: String,
        status: FeedbackStatus,
    ): Result<Unit>
}

@Serializable
internal data class FeedbackDiagnosticsWire(
    val appVersion: String,
    val android: String,
    val device: String,
)

@Serializable
internal data class FeedbackSubmitWire(
    val kind: String,
    val text: String,
    val diagnostics: FeedbackDiagnosticsWire? = null,
) {
    override fun toString(): String = "FeedbackSubmitWire(kind=$kind, text=<redacted>)"
}

@Serializable
internal data class FeedbackStatusWire(
    val status: String,
)

@Serializable
internal data class FeedbackCreatedWire(
    val id: String,
)

// Items stay raw elements until HubClient decodes them one by one, so one bad item costs only itself.
@Serializable
internal data class FeedbackListWire(
    val items: List<JsonElement>,
)

@Serializable
internal data class FeedbackItemWire(
    val id: String,
    val kind: String,
    val text: String,
    val status: String,
    val nickname: String,
    val createdAt: Long,
    val appVersion: String? = null,
    val android: String? = null,
    val device: String? = null,
    val issueUrl: String? = null,
) {
    // Null when the kind or status is one this build does not know.
    fun toItemOrNull(): FeedbackItem? {
        val kind = FeedbackKind.entries.firstOrNull { it.wire == kind } ?: return null
        val status = FeedbackStatus.entries.firstOrNull { it.wire == status } ?: return null
        val diagnostics =
            if (appVersion == null && android == null && device == null) {
                null
            } else {
                FeedbackDiagnostics(appVersion.orEmpty(), android.orEmpty(), device.orEmpty())
            }
        return FeedbackItem(id, kind, text, status, nickname, createdAt, diagnostics, issueUrl)
    }
}

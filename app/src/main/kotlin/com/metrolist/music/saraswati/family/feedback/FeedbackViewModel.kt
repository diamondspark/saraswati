/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: feedback — the "Send feedback" screen's state: the draft a person is typing, what goes with it, and the
// outcome of sending it.
//
// The draft lives here, not in the screen, so it survives rotation and a failed send; only a send the hub accepted
// clears the text. The report is untrusted-in-transit private text: it is never logged and a failure is reduced to a
// [FeedbackFailure] the screen words, never an exception message. The bearer is read for each send and never held.
package com.metrolist.music.saraswati.family.feedback

import android.os.Build
import androidx.lifecycle.ViewModel
import com.metrolist.music.BuildConfig
import com.metrolist.music.saraswati.family.FamilyError
import com.metrolist.music.saraswati.family.FamilyStore
import com.metrolist.music.saraswati.family.HubClient
import com.metrolist.music.saraswati.family.HubIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The hub counts UTF-16 code units, which is what [String.length] is, so the two ends agree on the cap. */
const val FEEDBACK_TEXT_MAX = 1000

data class FeedbackDraft(
    val kind: FeedbackKind = FeedbackKind.Bug,
    val text: String = "",
    val includeInfo: Boolean = true,
) {
    /** What is sent and counted: the text without its leading and trailing whitespace. */
    val trimmed: String get() = text.trim()

    val isValid: Boolean get() = trimmed.length in 1..FEEDBACK_TEXT_MAX
}

/** Why a send did not go through, in the terms a screen can word. */
enum class FeedbackFailure { NotPaired, Offline, RateLimited, NotSwitchedOn, Invalid, Other }

sealed interface FeedbackUiState {
    data object Idle : FeedbackUiState

    data object Sending : FeedbackUiState

    data object Sent : FeedbackUiState

    data class Error(
        val reason: FeedbackFailure,
    ) : FeedbackUiState
}

internal fun feedbackFailureOf(failure: Throwable): FeedbackFailure =
    when (FamilyError.from(failure)) {
        FamilyError.OFFLINE -> FeedbackFailure.Offline
        FamilyError.RATE_LIMITED -> FeedbackFailure.RateLimited
        // 404: a hub that has no /feedback yet. SERVER: the hub's 503 when the owner switched feedback off.
        FamilyError.NOT_FOUND, FamilyError.SERVER -> FeedbackFailure.NotSwitchedOn
        FamilyError.INVALID_INPUT -> FeedbackFailure.Invalid
        // The hub refused this phone's session (revoked) or the owner's key; either way it has to be set up again.
        FamilyError.UNAUTHORIZED, FamilyError.NO_ADMIN_KEY -> FeedbackFailure.NotPaired
        else -> FeedbackFailure.Other
    }

/** The only facts that ever leave with a report. */
fun interface FeedbackDiagnosticsProvider {
    fun current(): FeedbackDiagnostics
}

internal fun buildDiagnostics(
    versionName: String,
    release: String,
    sdkInt: Int,
    manufacturer: String,
    model: String,
) = FeedbackDiagnostics(
    appVersion = versionName,
    android = "$release (API $sdkInt)",
    device = "$manufacturer $model",
)

class AndroidFeedbackDiagnostics : FeedbackDiagnosticsProvider {
    override fun current(): FeedbackDiagnostics =
        buildDiagnostics(
            BuildConfig.VERSION_NAME,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            Build.MANUFACTURER,
            Build.MODEL,
        )
}

@HiltViewModel
class FeedbackViewModel
    internal constructor(
        private val hub: FeedbackHub,
        private val store: FamilyStore,
        private val identity: HubIdentity,
        diagnosticsProvider: FeedbackDiagnosticsProvider,
        private val scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(hub: HubClient, store: FamilyStore, identity: HubIdentity) :
            this(hub, store, identity, AndroidFeedbackDiagnostics(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        private val _draft = MutableStateFlow(FeedbackDraft())
        val draft: StateFlow<FeedbackDraft> = _draft.asStateFlow()

        private val _state = MutableStateFlow<FeedbackUiState>(FeedbackUiState.Idle)
        val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

        /** Shown under the switch so the person sees exactly what would be sent. */
        val diagnostics: FeedbackDiagnostics = diagnosticsProvider.current()

        fun setKind(kind: FeedbackKind) {
            _draft.update { it.copy(kind = kind) }
        }

        fun setText(text: String) {
            _draft.update { it.copy(text = text) }
            clearOutcome()
        }

        fun setIncludeInfo(include: Boolean) {
            _draft.update { it.copy(includeInfo = include) }
        }

        /** "Send another": back to a blank form (the text was already cleared when the send succeeded). */
        fun sendAnother() {
            clearOutcome()
        }

        fun send() {
            val draft = _draft.value
            if (!draft.isValid || _state.value == FeedbackUiState.Sending) return
            _state.value = FeedbackUiState.Sending
            scope.launch {
                val bearer = bearer()
                if (bearer == null) {
                    _state.value = FeedbackUiState.Error(FeedbackFailure.NotPaired)
                    return@launch
                }
                val result =
                    // Leaving the screen must not cancel a report the hub may already have accepted, which would
                    // look unsent and be sent again.
                    withContext(NonCancellable) {
                        hub.submitFeedback(bearer, draft.kind, draft.trimmed, diagnostics.takeIf { draft.includeInfo })
                    }
                result
                    .onSuccess {
                        // Only what was sent is cleared: text typed while the send was in flight stays.
                        _draft.update { now -> if (now.text == draft.text) now.copy(text = "") else now }
                        _state.value = FeedbackUiState.Sent
                    }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        _state.value = FeedbackUiState.Error(feedbackFailureOf(failure))
                    }
            }
        }

        private fun clearOutcome() {
            if (_state.value != FeedbackUiState.Sending) _state.value = FeedbackUiState.Idle
        }

        // The owner's phone sends with the admin key; every other phone with its session token. Neither is kept.
        private suspend fun bearer(): String? = guarded { store.adminKey() } ?: guarded { identity.sessionToken() }

        private suspend fun guarded(read: suspend () -> String?): String? =
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
    }

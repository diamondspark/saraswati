/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 11 — the Poochho Saraswati sheet's logic. A question (typed, or spoken and sent at once)
// goes to the hub through AskRepository; what comes back is carried out by AskExecutor. An answer is shown as
// plain text and is never acted on. Nothing here logs the question or the answer.
package com.metrolist.music.saraswati.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.StationsCache
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

sealed interface AskUiState {
    data object Idle : AskUiState

    data object Busy : AskUiState

    data class Answer(
        val text: String,
    ) : AskUiState

    data class Limit(
        val resetAt: Instant,
    ) : AskUiState
}

@HiltViewModel
class AskViewModel
    internal constructor(
        private val repo: AskRepository,
        private val stations: StationsCache,
        private val mixStore: ParivaarMixStore,
        private val gate: FamilyFeatureGate,
        scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(
            repo: AskRepository,
            stations: StationsCache,
            mixStore: ParivaarMixStore,
            gate: FamilyFeatureGate,
        ) : this(repo, stations, mixStore, gate, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        private val _state = MutableStateFlow<AskUiState>(AskUiState.Idle)
        val state: StateFlow<AskUiState> = _state.asStateFlow()

        /** False while the hub's ask_saraswati switch is off; the entry point is hidden then. */
        val enabled: StateFlow<Boolean> =
            gate.enabled
                .map { it[FamilyFeature.ASK_SARASWATI] != false }
                .stateIn(viewModelScope, SharingStarted.Eagerly, gate.isEnabled(FamilyFeature.ASK_SARASWATI))

        private var actions: AskActions? = null
        private var lastQuestion: String = ""
        private var job: Job? = null

        /** The screen's way to act on an intent; set before the first question is sent. */
        fun attach(actions: AskActions) {
            this.actions = actions
        }

        /** A typed question. Blank input, a question already in flight, or a switched-off feature sends nothing. */
        fun send(text: String) {
            val question = text.trim()
            if (question.isEmpty() || _state.value == AskUiState.Busy || !enabled.value) return
            lastQuestion = question
            _state.value = AskUiState.Busy
            job?.cancel()
            job =
                viewModelScope.launch {
                    when (val outcome = repo.ask(question)) {
                        is AskOutcome.Reply -> {
                            val result =
                                actions?.let { AskExecutor(it, stations, mixStore, gate).execute(outcome.reply.intent, question) }
                            _state.value =
                                when (result) {
                                    is ExecResult.ShowAnswer -> AskUiState.Answer(result.text)
                                    else -> AskUiState.Idle
                                }
                        }
                        is AskOutcome.LimitReached -> _state.value = AskUiState.Limit(outcome.resetAt)
                        AskOutcome.Unavailable -> {
                            actions?.openSearch(question)
                            _state.value = AskUiState.Idle
                        }
                    }
                }
        }

        /** The phone's speech result is sent exactly like typed text: no confirm step. */
        fun onVoiceResult(text: String) = send(text)

        /** "Search this" on an answer, and "Search instead" on the daily limit: the question goes to search as typed. */
        fun searchThis() {
            if (lastQuestion.isNotEmpty()) actions?.openSearch(lastQuestion)
        }
    }

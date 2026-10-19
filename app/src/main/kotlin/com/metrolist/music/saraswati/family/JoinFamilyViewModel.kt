/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the claiming phone's side of pairing. A pairing link
// (or a pasted token) becomes a claim with the role the person chose; the
// phone then polls the hub with its claim secret until the owner approves,
// and keeps the session token it is handed.
//
// The poll interval is 6 s: the hub allows 12 status polls a minute per
// device and answers 429 beyond that, so the loop stays well inside it and
// backs off further on a 429. The claim is stored the moment the hub issues
// it, so closing the app while waiting resumes the wait instead of burning
// the owner's one-time link.
package com.metrolist.music.saraswati.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface JoinUiState {
    /** Nothing to join yet: the screen offers a place to paste a link. */
    data object Idle : JoinUiState

    data class Ready(
        val token: String,
    ) : JoinUiState {
        override fun toString(): String = "Ready(token=<redacted>)"
    }

    data object Claiming : JoinUiState

    /** Claimed; waiting for the owner to approve. */
    data object Waiting : JoinUiState

    /** Approved and paired. [role] is what the owner set this phone up as. */
    data class Approved(
        val role: DeviceRole,
    ) : JoinUiState

    /** This phone already belongs to the family (or is the owner's). */
    data object AlreadyPaired : JoinUiState

    data class Failed(
        val error: FamilyError,
    ) : JoinUiState
}

@HiltViewModel
class JoinFamilyViewModel
    internal constructor(
        private val hub: HubApi,
        private val store: FamilyStore,
        private val config: HubConfig,
        private val inbox: PairingInbox,
        scope: CoroutineScope,
        private val pollIntervalMs: Long,
    ) : ViewModel(scope) {
        @Inject
        constructor(hub: HubApi, store: FamilyStore, config: HubConfig, inbox: PairingInbox) :
            this(hub, store, config, inbox, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), POLL_INTERVAL_MS)

        private val _state = MutableStateFlow<JoinUiState>(JoinUiState.Idle)
        val state: StateFlow<JoinUiState> = _state.asStateFlow()

        @Volatile private var token: String? = null

        @Volatile private var chosenRole = DeviceRole.ADULT
        private var pollJob: Job? = null

        /** Call when the join screen opens: resumes a stored claim, or picks up a link that arrived as a deep link. */
        fun start() {
            viewModelScope.launch {
                if (pollJob?.isActive == true) return@launch
                if (alreadyPaired()) {
                    inbox.take()
                    _state.value = JoinUiState.AlreadyPaired
                    return@launch
                }
                val claim = guarded { store.pendingClaim() }
                if (claim != null) {
                    inbox.take()
                    _state.value = JoinUiState.Waiting
                    poll(claim)
                    return@launch
                }
                inbox.take()?.let {
                    token = it
                    _state.value = JoinUiState.Ready(it)
                }
            }
        }

        /** A pasted link, a shared message containing one, or a bare token. */
        fun submitInput(text: String) {
            val found = PairingLink.tokenFromInput(text, config.baseUrl)
            if (found == null) {
                _state.value = JoinUiState.Failed(FamilyError.INVALID_INPUT)
                return
            }
            token = found
            _state.value = JoinUiState.Ready(found)
        }

        fun claim(
            deviceName: String,
            role: DeviceRole,
        ) {
            val link = token
            val name = deviceName.trim().take(MAX_NAME_LENGTH)
            if (link == null || name.isEmpty()) {
                _state.value = JoinUiState.Failed(FamilyError.INVALID_INPUT)
                return
            }
            viewModelScope.launch {
                _state.value = JoinUiState.Claiming
                hub
                    .claimPairing(link, name, role)
                    .onSuccess { claimed ->
                        // The link is spent whether or not storing the claim works.
                        token = null
                        chosenRole = role
                        val pending = PendingClaim(claimed.deviceId, claimed.claimSecret)
                        if (guardedWrite { store.savePendingClaim(pending) }) {
                            _state.value = JoinUiState.Waiting
                            poll(pending)
                        }
                    }.onFailure { failure ->
                        val error = FamilyError.from(failure)
                        if (error == FamilyError.GONE || error == FamilyError.NOT_FOUND) token = null
                        _state.value = JoinUiState.Failed(error)
                    }
            }
        }

        private fun poll(claim: PendingClaim) {
            pollJob?.cancel()
            pollJob =
                viewModelScope.launch {
                    var wait = pollIntervalMs
                    while (true) {
                        delay(wait)
                        val answer = hub.pairingStatus(claim.deviceId, claim.claimSecret)
                        answer
                            .onSuccess { status ->
                                wait = pollIntervalMs
                                if (status is PairingStatus.Approved) {
                                    approved(claim, status.sessionToken)
                                    return@launch
                                }
                            }.onFailure { failure ->
                                when (FamilyError.from(failure)) {
                                    FamilyError.NOT_FOUND -> {
                                        guardedWrite { store.clearPendingClaim() }
                                        _state.value = JoinUiState.Failed(FamilyError.NOT_FOUND)
                                        return@launch
                                    }
                                    FamilyError.RATE_LIMITED -> wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MS)
                                    else -> Unit
                                }
                            }
                    }
                }
        }

        private suspend fun approved(
            claim: PendingClaim,
            sessionToken: String,
        ) {
            if (!guardedWrite { store.completeClaim(claim.deviceId, sessionToken) }) return
            // The first authenticated call also lets the hub drop the claim secret.
            val role = hub.getMe(sessionToken).getOrNull()?.role ?: chosenRole
            _state.value = JoinUiState.Approved(role)
        }

        private suspend fun alreadyPaired(): Boolean = guarded { store.session() } != null || guarded { store.adminKey() } != null

        private suspend fun <T> guarded(block: suspend () -> T): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        /** Runs a storage write; a failure becomes the screen's error state and returns false. */
        private suspend fun guardedWrite(block: suspend () -> Unit): Boolean =
            try {
                block()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = JoinUiState.Failed(FamilyError.UNKNOWN)
                false
            }

        companion object {
            const val MAX_NAME_LENGTH = 40
            const val POLL_INTERVAL_MS = 6_000L
            private const val MAX_BACKOFF_MS = 60_000L
        }
    }

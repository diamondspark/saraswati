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
// it, so reopening Join after the app was closed resumes the wait instead of
// burning the owner's one-time link.
//
// Fix wave B1 (final review I3): the poll is bounded. It runs only while the
// Join screen is resumed: the screen calls stop() on pause and start() on
// resume, so nothing polls while Join is in the background or left. One waiting
// session (the time since start() or "Check again") lasts at most 15 minutes;
// then the state is StillWaiting and the screen offers a button, which asks once
// at once and begins a new session. Each resume is a new session.
//
// Task 9: once approved, the first getMe() both reports the role and caches it
// (GrandparentRoleSync), which is what turns Saral on for a grandparent phone.
package com.metrolist.music.saraswati.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.saraswati.family.sangat.SangatPushRegistrar
import com.metrolist.music.saraswati.saral.GrandparentRoleSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Claimed; waiting for the owner to approve. [code] is the four characters the person says to the owner. */
    data class Waiting(
        val code: String,
    ) : JoinUiState

    /** The wait hit its 15-minute cap and nothing is polling; the screen offers a Check again button. */
    data class StillWaiting(
        val code: String,
    ) : JoinUiState

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
        private val roleSync: GrandparentRoleSync,
        scope: CoroutineScope,
        private val pollIntervalMs: Long,
        private val maxWaitMs: Long = MAX_WAIT_MS,
        private val now: () -> Long = { System.nanoTime() / 1_000_000 },
        // SARASWATI: Sangat push — runs once pairing has stored the session, so this phone's push token reaches the hub.
        private val onPaired: () -> Unit = {},
    ) : ViewModel(scope) {
        @Inject
        constructor(
            hub: HubApi,
            store: FamilyStore,
            config: HubConfig,
            inbox: PairingInbox,
            roleSync: GrandparentRoleSync,
            pushRegistrar: SangatPushRegistrar,
        ) : this(
            hub,
            store,
            config,
            inbox,
            roleSync,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            POLL_INTERVAL_MS,
            onPaired = pushRegistrar::ensureRegisteredAsync,
        )

        private val _state = MutableStateFlow<JoinUiState>(JoinUiState.Idle)
        val state: StateFlow<JoinUiState> = _state.asStateFlow()

        @Volatile private var token: String? = null

        @Volatile private var chosenRole = DeviceRole.ADULT
        private var pollJob: Job? = null

        // True between stop() and the next start(): a claim that finishes in that gap keeps its Waiting
        // state but does not begin polling behind a screen nobody is looking at.
        @Volatile private var paused = false

        /**
         * Call when the join screen opens and each time it resumes: resumes a stored claim, or
         * picks up a link that arrived as a deep link. Safe to repeat. Once the claim is in flight,
         * the wait has started or it has finished, a newly arrived link is dropped, not kept for
         * a later visit, and the state is left alone.
         */
        fun start() {
            paused = false
            viewModelScope.launch {
                if (pollJob?.isActive == true || _state.value is JoinUiState.Approved || _state.value is JoinUiState.Claiming) {
                    inbox.take()
                    return@launch
                }
                // A wait that gave up stays given up until the person presses Check again.
                if (_state.value is JoinUiState.StillWaiting) {
                    inbox.take()
                    return@launch
                }
                if (alreadyPaired()) {
                    inbox.take()
                    _state.value = JoinUiState.AlreadyPaired
                    return@launch
                }
                val claim = guarded { store.pendingClaim() }
                if (claim != null) {
                    inbox.take()
                    _state.value = JoinUiState.Waiting(ClaimCode.of(claim.deviceId))
                    // SARASWATI: fix wave B3 — the reads above suspend, and the screen may have been paused in
                    // that time (stop() found no poll to end); polling now would run behind a screen nobody sees.
                    if (!paused) poll(claim)
                    return@launch
                }
                inbox.take()?.let {
                    token = it
                    _state.value = JoinUiState.Ready(it)
                }
            }
        }

        /**
         * Call when the join screen is paused (not visible): ends the poll. The state is left as it is, so
         * the person returns to the same screen, and [start] on the next resume polls again.
         */
        fun stop() {
            paused = true
            pollJob?.cancel()
            pollJob = null
        }

        /** The "Check again" button of [JoinUiState.StillWaiting]: asks the hub now, then waits a fresh 15 minutes. */
        fun checkAgain() {
            paused = false
            viewModelScope.launch {
                if (_state.value !is JoinUiState.StillWaiting) return@launch
                if (alreadyPaired()) {
                    _state.value = JoinUiState.AlreadyPaired
                    return@launch
                }
                val claim = guarded { store.pendingClaim() }
                if (claim == null) {
                    _state.value = JoinUiState.Idle
                    return@launch
                }
                _state.value = JoinUiState.Waiting(ClaimCode.of(claim.deviceId))
                // SARASWATI: fix wave B3 — as in start(): a pause during the reads above means no poll.
                if (!paused) poll(claim, askAtOnce = true)
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
                // SARASWATI: fix wave B2 — if the stored session slot cannot even be probed, nothing is known
                // about this phone, so the link is kept for a retry rather than spent on a claim that might
                // then be refused because a session turns out to be there.
                if (guarded { store.hasStoredSession() } == null) {
                    _state.value = JoinUiState.Failed(FamilyError.UNKNOWN)
                    return@launch
                }
                // SARASWATI: fix wave A (M3) — checked again right before the one-time link is spent: a phone
                // that is paired by now (or is the owner's) must neither burn the link nor lose its session.
                if (alreadyPaired()) {
                    token = null
                    _state.value = JoinUiState.AlreadyPaired
                    return@launch
                }
                hub
                    .claimPairing(link, name, role)
                    .onSuccess { claimed ->
                        // The link is spent whether or not storing the claim works.
                        token = null
                        chosenRole = role
                        val pending = PendingClaim(claimed.deviceId, claimed.claimSecret)
                        if (guardedWrite { store.savePendingClaim(pending) }) {
                            _state.value = JoinUiState.Waiting(ClaimCode.of(pending.deviceId))
                            if (!paused) poll(pending)
                        }
                    }.onFailure { failure ->
                        val error = FamilyError.from(failure)
                        if (error == FamilyError.GONE || error == FamilyError.NOT_FOUND) token = null
                        _state.value = JoinUiState.Failed(error)
                    }
            }
        }

        /** One waiting session: polls until approved, refused, stopped, or [maxWaitMs] have passed. */
        private fun poll(
            claim: PendingClaim,
            askAtOnce: Boolean = false,
        ) {
            pollJob?.cancel()
            pollJob =
                viewModelScope.launch {
                    val startedAt = now()
                    var wait = pollIntervalMs
                    var skipDelay = askAtOnce
                    while (true) {
                        val remaining = maxWaitMs - (now() - startedAt)
                        if (remaining <= 0) {
                            _state.value = JoinUiState.StillWaiting(ClaimCode.of(claim.deviceId))
                            return@launch
                        }
                        if (skipDelay) {
                            skipDelay = false
                        } else {
                            delay(minOf(wait, remaining))
                            if (now() - startedAt >= maxWaitMs) continue
                        }
                        val answer = hub.pairingStatus(claim.deviceId, claim.claimSecret)
                        answer
                            .onSuccess { status ->
                                wait = pollIntervalMs
                                if (status is PairingStatus.Approved) {
                                    // Once the hub has handed over the session token it must be kept, so leaving the
                                    // screen at this moment cannot cancel the write.
                                    withContext(NonCancellable) { approved(claim, status.sessionToken) }
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
            onPaired() // SARASWATI: Sangat push
            // The first authenticated call also lets the hub drop the claim secret. Not throttled: it is what
            // caches the role, and a resume refresh may have run a moment ago with no session to ask with.
            val role = roleSync.refresh(force = true) ?: chosenRole
            _state.value = JoinUiState.Approved(role)
        }

        // SARASWATI: fix wave B2 — the raw session slot is asked first. session() decrypts, and a Keystore that
        // is unavailable right now (or a hub-side hiccup that shows up as a read error) makes a live session
        // read as absent: the claim would then spend the one-time link and savePendingClaim would refuse it.
        private suspend fun alreadyPaired(): Boolean =
            guarded { store.hasStoredSession() } == true ||
                guarded { store.session() } != null ||
                guarded { store.adminKey() } != null

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

            /** How long one waiting session polls before it says it is still waiting. */
            const val MAX_WAIT_MS = 15 * 60_000L
            private const val MAX_BACKOFF_MS = 60_000L
        }
    }

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the owner's side of the family hub: create a pairing
// link, see who asked to join, approve, revoke, switch a phone's role, read
// the health view.
//
// Nothing here throws at a screen. Every hub or storage failure becomes a
// FamilyError in [error] (or in the state of the one flow it belongs to), and
// the data already shown stays. The admin key is read from the store for each
// action and never held in a field, a state or a log line; the session token
// the hub returns on approve is not kept anywhere (it belongs to the claiming
// phone, which collects it itself).
package com.metrolist.music.saraswati.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface PairingUiState {
    data object Idle : PairingUiState

    data object Creating : PairingUiState

    /** [link] is what to share or show as a QR code; [expiresAt] is the hub's ISO-8601 string. */
    data class Ready(
        val token: String,
        val link: String,
        val expiresAt: String,
    ) : PairingUiState {
        override fun toString(): String = "Ready(token=<redacted>, link=<redacted>, expiresAt=$expiresAt)"
    }

    data class Error(
        val error: FamilyError,
    ) : PairingUiState
}

sealed interface OwnerSetupState {
    data object Idle : OwnerSetupState

    data object Checking : OwnerSetupState

    data object Done : OwnerSetupState

    data class Failed(
        val error: FamilyError,
    ) : OwnerSetupState
}

@HiltViewModel
class FamilyViewModel
    internal constructor(
        private val hub: HubApi,
        private val store: FamilyStore,
        private val config: HubConfig,
        scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(hub: HubApi, store: FamilyStore, config: HubConfig) :
            this(hub, store, config, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        private val _pairingState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
        val pairingState: StateFlow<PairingUiState> = _pairingState.asStateFlow()

        private val _roster = MutableStateFlow<List<RosterEntry>>(emptyList())
        val roster: StateFlow<List<RosterEntry>> = _roster.asStateFlow()

        private val _health = MutableStateFlow<List<DeviceHealth>>(emptyList())
        val health: StateFlow<List<DeviceHealth>> = _health.asStateFlow()

        private val _error = MutableStateFlow<FamilyError?>(null)

        /** The last failure of a roster, health or device action; null once something succeeds or [clearError] runs. */
        val error: StateFlow<FamilyError?> = _error.asStateFlow()

        private val _setupState = MutableStateFlow<OwnerSetupState>(OwnerSetupState.Idle)
        val setupState: StateFlow<OwnerSetupState> = _setupState.asStateFlow()

        private val _isMember = MutableStateFlow(false)

        /** True once the owner approved this phone (it holds a hub session). Set by [refreshMembership]. */
        val isMember: StateFlow<Boolean> = _isMember.asStateFlow()

        fun refreshMembership() {
            viewModelScope.launch {
                _isMember.value =
                    try {
                        store.session() != null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
            }
        }

        fun clearError() {
            _error.value = null
        }

        fun startPairing() {
            viewModelScope.launch {
                _pairingState.value = PairingUiState.Creating
                val key = adminKey()
                if (key == null) {
                    _pairingState.value = PairingUiState.Error(FamilyError.NO_ADMIN_KEY)
                    return@launch
                }
                _pairingState.value =
                    hub.createPairing(key).fold(
                        onSuccess = { created ->
                            val link = PairingLink.build(config.baseUrl, created.token)
                            if (link == null) {
                                PairingUiState.Error(FamilyError.UNKNOWN)
                            } else {
                                PairingUiState.Ready(created.token, link, created.expiresAt)
                            }
                        },
                        onFailure = { PairingUiState.Error(FamilyError.from(it)) },
                    )
            }
        }

        fun refreshRoster() = ownerAction { key -> loadRoster(key) }

        fun refreshHealth() = ownerAction { key -> loadHealth(key) }

        fun refreshOwnerData() =
            ownerAction { key ->
                loadRoster(key)
                loadHealth(key)
            }

        fun approve(deviceId: String) =
            ownerAction { key ->
                hub
                    .approveDevice(key, deviceId)
                    .onSuccess { loadRoster(key) }
                    .onFailure { fail(it) }
            }

        fun revoke(deviceId: String) =
            ownerAction { key ->
                hub
                    .revokeDevice(key, deviceId)
                    .onSuccess { loadRoster(key) }
                    .onFailure { fail(it) }
            }

        /** The owner's only way to switch a phone between adult and grandparent. */
        fun setRole(
            deviceId: String,
            role: DeviceRole,
        ) = ownerAction { key ->
            hub
                .setRole(key, deviceId, role)
                .onSuccess { loadRoster(key) }
                .onFailure { fail(it) }
        }

        /**
         * First-time owner setup: the pasted admin key is kept only if the hub
         * accepts it, so a typo is never stored.
         */
        fun saveAdminKey(pasted: String) {
            val key = pasted.trim()
            if (key.isEmpty()) {
                _setupState.value = OwnerSetupState.Failed(FamilyError.INVALID_INPUT)
                return
            }
            viewModelScope.launch {
                _setupState.value = OwnerSetupState.Checking
                hub
                    .getRoster(key)
                    .onSuccess { devices ->
                        _setupState.value =
                            try {
                                store.saveOwner(key)
                                _roster.value = devices
                                _error.value = null
                                OwnerSetupState.Done
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                OwnerSetupState.Failed(FamilyError.UNKNOWN)
                            }
                    }.onFailure { _setupState.value = OwnerSetupState.Failed(FamilyError.from(it)) }
            }
        }

        private fun ownerAction(block: suspend (adminKey: String) -> Unit) {
            viewModelScope.launch {
                _error.value = null
                val key = adminKey()
                if (key == null) {
                    _error.value = FamilyError.NO_ADMIN_KEY
                    return@launch
                }
                block(key)
            }
        }

        private suspend fun loadRoster(key: String) {
            hub.getRoster(key).onSuccess { _roster.value = it }.onFailure { fail(it) }
        }

        private suspend fun loadHealth(key: String) {
            hub.getHealth(key).onSuccess { _health.value = it }.onFailure { fail(it) }
        }

        private fun fail(failure: Throwable) {
            _error.value = FamilyError.from(failure)
        }

        private suspend fun adminKey(): String? =
            try {
                store.adminKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
    }

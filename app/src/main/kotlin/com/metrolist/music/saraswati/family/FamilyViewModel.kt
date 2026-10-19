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
//
// Task 8 adds the station list and the four kill-switches. A station edit is
// checked on the phone first (one to six stations; a name; a video that
// resolves) so the owner gets an answer at once, then the whole resulting list
// is sent. A list that is not complete yet (the four starting defaults have no
// video until the owner picks one) is kept as an unsaved draft, never sent.
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
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

/** [DRAFT] is a station list that is not on the hub yet: the starting defaults, or edits still missing a video. */
enum class StationsState { NOT_LOADED, SYNCED, DRAFT }

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
        private val newStationId: () -> String = { UUID.randomUUID().toString() },
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

        private val _stations = MutableStateFlow<List<Station>>(emptyList())

        /** The station list being edited: the hub's, or the four starting defaults on a first run. */
        val stations: StateFlow<List<Station>> = _stations.asStateFlow()

        private val _stationsState = MutableStateFlow(StationsState.NOT_LOADED)
        val stationsState: StateFlow<StationsState> = _stationsState.asStateFlow()

        private val _killSwitches = MutableStateFlow(allFeaturesOn())

        /** Every [FamilyFeature] key, on unless the hub said off. */
        val killSwitches: StateFlow<Map<String, Boolean>> = _killSwitches.asStateFlow()

        // Station edits send a whole list, so two overlapping edits must not both start from the same one.
        private val stationsLock = Mutex()

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

        /** Loads the station list and the kill-switches. Unsaved station edits are kept, not reloaded over. */
        fun loadSettings() =
            ownerAction { key ->
                loadStations(key)
                loadKillSwitches(key)
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

        fun setStations(list: List<Station>) = editStations { list }

        fun addStation(
            title: String,
            videoInput: String,
            iconIndex: Int,
        ) = editStations { current ->
            if (current.size >= MAX_STATIONS) {
                _error.value = FamilyError.STATION_LIMIT
                return@editStations null
            }
            val added = buildStation(newStationId(), title, videoInput, iconIndex, keepVideo = null)
            if (added == null) null else current + added
        }

        /** An empty [videoInput] keeps the station's current video. */
        fun updateStation(
            id: String,
            title: String,
            videoInput: String,
            iconIndex: Int,
        ) = editStations { current ->
            val old = current.firstOrNull { it.id == id } ?: return@editStations null
            val edited = buildStation(id, title, videoInput, iconIndex, keepVideo = old.seedVideoId)
            if (edited == null) null else current.map { if (it.id == id) edited else it }
        }

        fun removeStation(id: String) =
            editStations { current ->
                when {
                    current.none { it.id == id } -> null
                    current.size <= 1 -> {
                        _error.value = FamilyError.STATION_LIMIT
                        null
                    }
                    else -> current.filterNot { it.id == id }
                }
            }

        /** Moves a station [offset] places (-1 up, +1 down); past either end does nothing. */
        fun moveStation(
            id: String,
            offset: Int,
        ) = editStations { current ->
            val from = current.indexOfFirst { it.id == id }
            val to = from + offset
            if (from < 0 || to !in current.indices) {
                null
            } else {
                current.toMutableList().apply { add(to, removeAt(from)) }
            }
        }

        fun toggleKillSwitch(
            key: String,
            enabled: Boolean,
        ) = ownerAction { adminKey ->
            if (FamilyFeature.fromKey(key) == null) {
                _error.value = FamilyError.INVALID_INPUT
                return@ownerAction
            }
            hub
                .setKillSwitches(adminKey, mapOf(key to enabled))
                .onSuccess { _killSwitches.value = withEveryFeature(it) }
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

        private suspend fun loadStations(key: String) {
            stationsLock.withLock {
                if (_stationsState.value == StationsState.DRAFT) return
                hub
                    .getStations(key)
                    .onSuccess { fromHub ->
                        if (fromHub.isEmpty() && !stationsEverSaved()) {
                            _stations.value = DEFAULT_STATIONS
                            _stationsState.value = StationsState.DRAFT
                        } else {
                            _stations.value = fromHub
                            _stationsState.value = StationsState.SYNCED
                        }
                    }.onFailure { fail(it) }
            }
        }

        private suspend fun loadKillSwitches(key: String) {
            hub.getKillSwitches(key).onSuccess { _killSwitches.value = withEveryFeature(it) }.onFailure { fail(it) }
        }

        /** [build] returns the list to apply, or null for "nothing to do" (it sets the error itself when that is a refusal). */
        private fun editStations(build: (current: List<Station>) -> List<Station>?) {
            viewModelScope.launch {
                _error.value = null
                stationsLock.withLock {
                    if (_stationsState.value == StationsState.NOT_LOADED) {
                        _error.value = FamilyError.UNKNOWN
                        return@withLock
                    }
                    build(_stations.value)?.let { applyStations(it) }
                }
            }
        }

        private suspend fun applyStations(next: List<Station>) {
            if (next.size !in 1..MAX_STATIONS) {
                _error.value = FamilyError.STATION_LIMIT
                return
            }
            if (next.any { it.seedVideoId.isBlank() || it.title.isBlank() }) {
                _stations.value = next
                _stationsState.value = StationsState.DRAFT
                return
            }
            val key = adminKey()
            if (key == null) {
                _error.value = FamilyError.NO_ADMIN_KEY
                return
            }
            hub
                .setStations(key, next)
                .onSuccess { saved ->
                    _stations.value = saved
                    _stationsState.value = StationsState.SYNCED
                    try {
                        store.markStationsSaved()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The hub now holds the list, so an empty answer later cannot be mistaken for a first run.
                    }
                }.onFailure { fail(it) }
        }

        private fun buildStation(
            id: String,
            title: String,
            videoInput: String,
            iconIndex: Int,
            keepVideo: String?,
        ): Station? {
            val name = title.trim().take(MAX_TITLE_LENGTH)
            val video = if (videoInput.isBlank() && keepVideo != null) keepVideo else StationVideo.videoIdFrom(videoInput)
            if (name.isEmpty() || video == null) {
                _error.value = FamilyError.INVALID_INPUT
                return null
            }
            return Station(id, name, video, iconIndex.coerceIn(0, StationIcons.COUNT - 1))
        }

        private suspend fun stationsEverSaved(): Boolean =
            try {
                store.stationsEverSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
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

        companion object {
            const val MAX_STATIONS = 6
            const val MAX_TITLE_LENGTH = 40

            /**
             * What the editor starts on when nobody has saved a list yet: the
             * four stations this family's library points to, leaving two of the
             * six slots free. They have no video until the owner picks one, so
             * they are an unsaved draft, never sent as they are. The icon is the
             * radio-waves glyph every tile of the approved mockup uses.
             */
            val DEFAULT_STATIONS: List<Station> =
                listOf(
                    Station("default-bollywood-classics", "Bollywood Classics", "", 0),
                    Station("default-bhajans-devotional", "Bhajans & Devotional", "", 0),
                    Station("default-ghazals-old-melodies", "Ghazals & Old Melodies", "", 0),
                    Station("default-peaceful-meditation", "Peaceful & Meditation", "", 0),
                )

            private fun allFeaturesOn(): Map<String, Boolean> = FamilyFeature.entries.associate { it.key to true }

            private fun withEveryFeature(fromHub: Map<String, Boolean>): Map<String, Boolean> =
                FamilyFeature.entries.associate { it.key to FamilyFeature.isEnabled(fromHub, it) }
        }
    }

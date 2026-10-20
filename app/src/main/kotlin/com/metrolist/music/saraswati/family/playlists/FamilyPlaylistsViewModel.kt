/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 20 — the family playlists as the Family tab, the playlist screen and the song menu show
// them. Adding a song is offered only for playlists this phone made (before Task 23 confirms collaboration), and
// a failed create or add leaves a [FamilyError] for the screen to word, never an exception.
package com.metrolist.music.saraswati.family.playlists

import androidx.lifecycle.ViewModel
import com.metrolist.music.saraswati.family.FamilyError
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.FamilyPlaylist
import com.metrolist.music.saraswati.family.HubIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FamilyPlaylistsViewModel
    internal constructor(
        private val repository: FamilyPlaylistsRepository,
        private val identity: HubIdentity,
        private val features: StateFlow<Map<FamilyFeature, Boolean>>,
        scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(
            repository: FamilyPlaylistsRepository,
            identity: HubIdentity,
            gate: FamilyFeatureGate,
        ) : this(repository, identity, gate.enabled, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        private val scope: CoroutineScope = scope

        private val ownDevice = MutableStateFlow<String?>(null)

        private val _error = MutableStateFlow<FamilyError?>(null)

        /** The last create or add failure, null once one succeeds or [clearError] runs. */
        val error: StateFlow<FamilyError?> = _error

        /** The Family playlists switch as the hub last said it (on until it says otherwise). */
        val visible: StateFlow<Boolean> =
            features
                .map { featureOn(it) }
                .stateIn(scope, SharingStarted.Eagerly, featureOn(features.value))

        /** Every family playlist, oldest first; empty while the switch is off. */
        val playlists: StateFlow<List<FamilyPlaylist>> =
            repository.playlists.stateIn(scope, SharingStarted.Eagerly, repository.cached())

        /**
         * Ids of the playlists this phone made, before Task 23 lets other phones add. A flow so a screen that opens
         * before the device id is read updates when it arrives, instead of showing the creator "Read-only".
         */
        val madeHere: StateFlow<Set<String>> =
            combine(playlists, ownDevice) { all, own ->
                if (own == null) emptySet() else all.filter { it.addedByDeviceId == own }.mapTo(HashSet()) { it.id }
            }.stateIn(scope, SharingStarted.Eagerly, emptySet())

        /** The playlists this phone may add songs to, so the song menu offers only those. */
        val addable: StateFlow<List<FamilyPlaylist>> =
            combine(playlists, madeHere, visible) { all, mine, on ->
                if (!on) emptyList() else all.filter { it.id in mine }
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        init {
            scope.launch { ownDevice.value = identity.deviceId() }
        }

        /** Asks the hub for the family's list. A failure keeps what is shown. */
        suspend fun refresh() {
            repository.refresh()
        }

        suspend fun create(title: String): Result<FamilyPlaylist> =
            repository.create(title).also { result -> recordOutcome(result.exceptionOrNull()) }

        suspend fun addSong(
            p: FamilyPlaylist,
            videoId: String,
        ): Result<Unit> = repository.addSong(p, videoId).also { result -> recordOutcome(result.exceptionOrNull()) }

        fun clearError() {
            _error.value = null
        }

        private fun recordOutcome(failure: Throwable?) {
            if (failure == null) {
                _error.value = null
            } else if (failure !is CancellationException) {
                _error.value = FamilyError.from(failure)
            }
        }

        private fun featureOn(features: Map<FamilyFeature, Boolean>): Boolean = features[FamilyFeature.FAMILY_PLAYLISTS] ?: true
    }

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 20 — the Parivaar Mix as the Family tab and Home show it: the held mix, whether the
// section is on at all (the owner's parivaar_mix switch), and the taste toggle that lets Sangat and Parivaar Mix
// plays count toward this person's taste (CountSharedPlaysKey, off by default).
//
// Nothing here suspends on a screen's behalf except the toggle write, which runs on the view model's scope.
// A storage read that fails reads as the switch being off for the toggle and as the empty mix for the mix.
package com.metrolist.music.saraswati.family.parivaar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import com.metrolist.music.constants.CountSharedPlaysKey
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Parivaar Mix section shows. */
sealed interface ParivaarUiState {
    /** The owner has switched the Parivaar Mix off: no section, no Home card. */
    data object Hidden : ParivaarUiState

    /** On, but no mix yet: fewer than two family members have songs to build it from. */
    data object NeedsTwoMembers : ParivaarUiState

    data class Ready(
        val tracks: List<MixTrack>,
        val builtAt: Long,
    ) : ParivaarUiState
}

@HiltViewModel
class ParivaarViewModel
    internal constructor(
        private val store: ParivaarMixStore,
        private val preferences: DataStore<Preferences>,
        private val features: StateFlow<Map<FamilyFeature, Boolean>>,
        scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(
            store: ParivaarMixStore,
            gate: FamilyFeatureGate,
            @ApplicationContext context: Context,
        ) : this(
            store,
            context.dataStore,
            gate.enabled,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )

        private val scope: CoroutineScope = scope

        /** The Parivaar Mix switch as the hub last said it (on until it says otherwise). */
        val visible: StateFlow<Boolean> =
            features
                .map { parivaarOn(it) }
                .stateIn(scope, SharingStarted.Eagerly, parivaarOn(features.value))

        /** The taste toggle is shown while either shared feature is on: it changes how both count. */
        val tasteVisible: StateFlow<Boolean> =
            features
                .map { sangatOn(it) || parivaarOn(it) }
                .stateIn(scope, SharingStarted.Eagerly, features.value.let { sangatOn(it) || parivaarOn(it) })

        /** The mix held on this phone. Starts with what is already cached, so a screen does not wait for storage. */
        val mix: StateFlow<ParivaarMix> = store.mix.stateIn(scope, SharingStarted.Eagerly, store.cached())

        val state: StateFlow<ParivaarUiState> =
            combine(visible, mix) { on, held -> stateOf(on, held) }
                .stateIn(scope, SharingStarted.Eagerly, stateOf(visible.value, store.cached()))

        /** Whether Sangat and Parivaar Mix plays count toward this person's taste. */
        val countSharedPlays: StateFlow<Boolean> =
            preferences.data
                .catch { emit(emptyPreferences()) }
                .map { it[CountSharedPlaysKey] ?: false }
                .stateIn(scope, SharingStarted.Eagerly, false)

        /** Writes the taste toggle. A failed write leaves the stored value as it was. */
        fun setCountSharedPlays(on: Boolean) {
            scope.launch {
                try {
                    preferences.edit { it[CountSharedPlaysKey] = on }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The toggle keeps showing the stored value, which is what the person sees after a restart too.
                }
            }
        }

        /**
         * Starts the Parivaar Mix as a queue through [startQueue], with the tracks held right now. Does nothing
         * when the mix is empty or the section is off.
         */
        fun play(startQueue: (ParivaarMixQueue) -> Unit) {
            if (!visible.value) return
            val tracks = store.cached().tracks
            if (tracks.isEmpty()) return
            startQueue(ParivaarMixQueue(tracks))
        }

        private fun stateOf(
            on: Boolean,
            held: ParivaarMix,
        ): ParivaarUiState =
            when {
                !on -> ParivaarUiState.Hidden
                held.tracks.isEmpty() -> ParivaarUiState.NeedsTwoMembers
                else -> ParivaarUiState.Ready(held.tracks, held.builtAt)
            }

        private fun parivaarOn(features: Map<FamilyFeature, Boolean>): Boolean = features[FamilyFeature.PARIVAAR_MIX] ?: true

        private fun sangatOn(features: Map<FamilyFeature, Boolean>): Boolean = features[FamilyFeature.SANGAT] ?: true
    }

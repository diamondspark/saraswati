/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 15 — Parivaar Saar on the Family tab: this week's recap computed from the snapshots this
// phone holds, hidden while the owner has switched the Parivaar Mix off or the phone has no pairing. Playing the
// anthem starts its radio. Nothing here reaches the hub.
package com.metrolist.music.saraswati.family.recap

import androidx.lifecycle.ViewModel
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class FamilyRecapViewModel
    internal constructor(
        private val store: RecapSnapshotStore,
        private val identity: HubIdentity,
        private val features: StateFlow<Map<FamilyFeature, Boolean>>,
        private val today: () -> LocalDate,
        scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(
            store: RecapSnapshotStore,
            identity: HubIdentity,
            gate: FamilyFeatureGate,
        ) : this(
            store,
            identity,
            gate.enabled,
            { LocalDate.now() },
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )

        private val scope: CoroutineScope = scope
        private val myDeviceId = MutableStateFlow<String?>(null)

        init {
            scope.launch {
                store.ensureLoaded()
                myDeviceId.value = identity.deviceId()
            }
        }

        /** This week's recap, or null when there is none to show (no snapshots, no pairing, or the mix is switched off). */
        val recap: StateFlow<FamilyRecap?> =
            combine(store.snapshots, features, myDeviceId) { snapshots, switches, me ->
                val on = switches[FamilyFeature.PARIVAAR_MIX] ?: true
                val current = snapshots.firstOrNull()
                if (!on || me == null || current == null) {
                    null
                } else {
                    FamilyRecapBuilder.build(current, RecapSnapshotStore.previousOf(snapshots), me, today())
                }
            }.stateIn(scope, SharingStarted.Eagerly, null)

        /** The radio the anthem starts, or null when there is no anthem. */
        fun anthemRadio(): WatchEndpoint? = recap.value?.anthem?.let { WatchEndpoint(videoId = it.videoId) }

        /** Starts the anthem's radio through [startQueue]. Does nothing without an anthem. */
        fun playAnthem(startQueue: (YouTubeQueue) -> Unit) {
            val endpoint = anthemRadio() ?: return
            startQueue(YouTubeQueue(endpoint))
        }
    }

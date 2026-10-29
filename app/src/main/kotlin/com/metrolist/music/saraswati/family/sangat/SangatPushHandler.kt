/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — what the phone does when the hub's data-only "sangat_changed" message arrives, with the
// app closed or open. The message carries nothing about the room: the phone asks the hub for the live list itself,
// then posts the "X started a Sangat" notification for each room it has not announced and takes down the
// notifications of rooms that have ended. A hub that cannot be reached, a missing session or Sangat switched off
// changes nothing, and the Sangat switch is read only after the stored switches are loaded, so an owner's "off" holds
// on a cold start. Rooms this phone hosts or is in, and rooms the person dismissed, never notify, nor do rooms already
// announced (kept across restarts in [AnnouncedRoomsStore], pruned to the live ones). A Saral phone notifies nowhere,
// as the foreground poll never runs there.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.LiveSangatRoom
import com.metrolist.music.saraswati.saral.SaralActive
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SangatPushHandler
    internal constructor(
        private val awaitGate: suspend () -> Unit,
        private val fetchLive: suspend () -> List<LiveSangatRoom>?,
        private val claimUnannounced: (List<LiveSangatRoom>) -> List<LiveSangatRoom>,
        private val selfDeviceId: suspend () -> String?,
        private val currentRoomCode: suspend () -> String?,
        private val dismissed: () -> Set<String>,
        private val isSaral: () -> Boolean,
        private val notifier: SangatNotifier,
        private val announcedRooms: AnnouncedRoomsStore,
    ) {
        @Inject
        constructor(
            gate: FamilyFeatureGate,
            watcher: SangatLiveWatcher,
            identity: HubIdentity,
            dismissals: SangatDismissals,
            port: SangatRoomPort,
            notifier: SangatNotifier,
            announcedRooms: AnnouncedRoomsStore,
        ) : this(
            awaitGate = gate::awaitHydrated,
            fetchLive = watcher::fetchLiveRooms,
            claimUnannounced = watcher::claimUnannounced,
            selfDeviceId = identity::deviceId,
            currentRoomCode = { port.currentRoomCode.first() },
            dismissed = { dismissals.dismissed.value },
            isSaral = { SaralActive.on },
            notifier = notifier,
            announcedRooms = announcedRooms,
        )

        suspend fun onSangatChanged() {
            if (isSaral()) return
            awaitGate()
            val live = fetchLive() ?: return
            val self = selfDeviceId()
            val visible = live.filter { self == null || it.hostDeviceId != self }
            val here = currentRoomCode()
            val gone = dismissed()
            val posted = notifier.postedRoomCodes()
            val seen = announcedRooms.read()

            SangatNotifyPolicy.toCancel(posted, visible, here, gone).forEach(notifier::cancel)
            SangatNotifyPolicy
                .toNotify(claimUnannounced(visible), gone, here, seen)
                .filter { it.roomCode !in posted }
                .forEach(notifier::post)

            // Every live room is announced now, posted or not; the ended ones are forgotten.
            val liveCodes = live.mapTo(HashSet()) { it.roomCode }
            val visibleCodes = visible.mapTo(HashSet()) { it.roomCode }
            announcedRooms.update { stored -> stored.filterTo(HashSet()) { it in liveCodes } + visibleCodes }
        }
    }

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — what the phone does when the hub's data-only "sangat_changed" message arrives, with the
// app closed or open. The message carries nothing about the room: the phone asks the hub for the live list itself,
// then posts the "X started a Sangat" notification for each room it has not announced and takes down the
// notifications of rooms that have ended. A hub that cannot be reached, a missing session or Sangat switched off
// changes nothing. Rooms this phone hosts or is in, and rooms the person dismissed, never notify. A Saral phone
// notifies nowhere, as the foreground poll never runs there.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.LiveSangatRoom
import com.metrolist.music.saraswati.saral.SaralActive
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SangatPushHandler
    internal constructor(
        private val fetchLive: suspend () -> List<LiveSangatRoom>?,
        private val claimUnannounced: (List<LiveSangatRoom>) -> List<LiveSangatRoom>,
        private val selfDeviceId: suspend () -> String?,
        private val currentRoomCode: suspend () -> String?,
        private val dismissed: () -> Set<String>,
        private val isSaral: () -> Boolean,
        private val notifier: SangatNotifier,
    ) {
        @Inject
        constructor(
            watcher: SangatLiveWatcher,
            identity: HubIdentity,
            dismissals: SangatDismissals,
            port: SangatRoomPort,
            notifier: SangatNotifier,
        ) : this(
            fetchLive = watcher::fetchLiveRooms,
            claimUnannounced = watcher::claimUnannounced,
            selfDeviceId = identity::deviceId,
            currentRoomCode = { port.currentRoomCode.first() },
            dismissed = { dismissals.dismissed.value },
            isSaral = { SaralActive.on },
            notifier = notifier,
        )

        suspend fun onSangatChanged() {
            if (isSaral()) return
            val live = fetchLive() ?: return
            val self = selfDeviceId()
            val visible = live.filter { self == null || it.hostDeviceId != self }
            val here = currentRoomCode()
            val gone = dismissed()
            val posted = notifier.postedRoomCodes()

            SangatNotifyPolicy.toCancel(posted, visible, here, gone).forEach(notifier::cancel)
            SangatNotifyPolicy
                .toNotify(claimUnannounced(visible), gone, here)
                .filter { it.roomCode !in posted }
                .forEach(notifier::post)
        }
    }

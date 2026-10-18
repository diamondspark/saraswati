/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: MusicService is android:exported="true" (required for
// Android Auto, Bluetooth and the media-button broadcast), so any app on
// the device can start it with any Intent. onStartCommand() still runs a
// when(intent?.action) over the home-screen-widget and alarm-trigger
// action strings even though the widget BroadcastReceivers that used to
// send them were deleted from the manifest (b87fcbe0, NEW-1) — nothing in
// this app sends these intents any more, but the action strings
// themselves are public (they live in these very companion objects), so
// any other app could still send them straight to the exported service
// and like a song, skip/previous a track, or fire an alarm playlist on
// the user's device (audit NEW-2). StartActions.isBlocked() lets
// MusicService refuse to run those actions regardless of who sent them.
// Media3's own actions (ACTION_MEDIA_BUTTON, notification-dismissal
// intents, a null action) are left alone — they are not in this set.
package com.metrolist.music.saraswati.security

import com.metrolist.music.playback.MusicService
import com.metrolist.music.widget.MusicWidgetReceiver
import com.metrolist.music.widget.PlaylistWidgetReceiver

object StartActions {
    // Referencing the real constants (not copying their string literals) so this set can't
    // silently drift out of sync if a widget/alarm action string ever changes.
    private val BLOCKED_ACTIONS: Set<String> =
        setOf(
            MusicService.ACTION_ALARM_TRIGGER,
            MusicWidgetReceiver.ACTION_PLAY_PAUSE,
            MusicWidgetReceiver.ACTION_LIKE,
            MusicWidgetReceiver.ACTION_NEXT,
            MusicWidgetReceiver.ACTION_PREVIOUS,
            MusicWidgetReceiver.ACTION_UPDATE_WIDGET,
            PlaylistWidgetReceiver.ACTION_UPDATE_WIDGET,
            PlaylistWidgetReceiver.ACTION_PLAY_TARGET,
        )

    fun isBlocked(action: String?): Boolean = action != null && action in BLOCKED_ACTIONS
}

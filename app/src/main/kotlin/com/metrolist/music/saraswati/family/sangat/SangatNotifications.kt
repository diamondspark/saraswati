/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 9 — the local notification for a family Sangat started while the app is alive. There is
// no push channel, so it is posted only while the watcher polls (the app on screen, or its media service alive).
// It has its own channel, so it can be muted without muting playback. Tapping it hands the room code to
// SangatJoinRequests, and the Sangat view model joins that room the same way the banner does.
// SARASWATI: device fix F2 — each notification is tagged with its room code, so a room that has ended, one this phone is
// in, or one dismissed is found again in the shade (even after the process died) and cancelled.
// SARASWATI: Sangat push — with the app closed the same notification is posted and cancelled by SangatPushHandler,
// when the hub's "a Sangat changed" message arrives.
package com.metrolist.music.saraswati.family.sangat

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metrolist.music.R
import com.metrolist.music.saraswati.family.LiveSangatRoom
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// SARASWATI: Sangat push — what the push handler needs of the notifications, so it runs against a plain fake in JUnit.
interface SangatNotifier {
    /** The room codes of the Sangat notifications still in the shade. */
    fun postedRoomCodes(): Set<String>

    fun post(room: LiveSangatRoom)

    fun cancel(roomCode: String)
}

@Singleton
class SangatNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val watcher: SangatLiveWatcher,
        private val dismissals: SangatDismissals,
        private val port: SangatRoomPort,
        // SARASWATI: Sangat push — rooms announced before, across restarts.
        private val announcedRooms: AnnouncedRoomsStore,
    ) : SangatNotifier {
        /** Posts a notification for each room the watcher announces. Called once, from the application scope. */
        fun start(scope: CoroutineScope) {
            scope.launch {
                watcher.carPrompts.collect { room ->
                    SangatNotifyPolicy
                        .toNotify(listOf(room), dismissals.dismissed.value, port.currentRoomCode.first(), announcedRooms.read())
                        .forEach(::post)
                    announcedRooms.update { it + room.roomCode } // SARASWATI: Sangat push — a later push does not post it again
                }
            }
            scope.launch {
                combine(watcher.answeredLive, port.currentRoomCode, dismissals.dismissed) { answered, here, dismissed ->
                    // Null is "the hub has not answered while we ran" (not polled yet, app in the background, or a failed
                    // poll), which says nothing about whether a room has ended.
                    if (answered == null) emptySet() else SangatNotifyPolicy.toCancel(postedRoomCodes(), answered, here, dismissed)
                }.collect { codes -> codes.forEach(::cancel) }
            }
            scope.launch {
                // SARASWATI: Sangat push — the hub answered, so the announced set forgets the rooms that ended (the push handler does the same).
                watcher.answeredLive.filterNotNull().collect { answered -> announcedRooms.pruneTo(answered.mapTo(HashSet()) { it.roomCode }) }
            }
        }

        /** The room codes of the Sangat notifications still in the shade, read back from the system. */
        override fun postedRoomCodes(): Set<String> =
            context
                .getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                .orEmpty()
                .filter { it.id == NOTIFICATION_ID }
                .mapNotNull { it.tag }
                .toSet()

        override fun cancel(roomCode: String) = NotificationManagerCompat.from(context).cancel(roomCode, NOTIFICATION_ID)

        @SuppressLint("MissingPermission")
        override fun post(room: LiveSangatRoom) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val tap =
                launch.apply {
                    action = SangatJoinRequests.ACTION_JOIN
                    putExtra(SangatJoinRequests.EXTRA_ROOM_CODE, room.roomCode)
                }
            val pending =
                PendingIntent.getActivity(
                    context,
                    room.roomCode.hashCode(),
                    tap,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.family_sangat_notify_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.family_sangat_notify_channel_desc) },
            )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.share)
                    .setContentTitle(context.getString(R.string.family_sangat_notify_title, room.hostName))
                    .setContentText(context.getString(R.string.family_sangat_notify_text))
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    // SARASWATI: Sangat push — the same room posted again (the push, then the foreground poll) never alerts twice.
                    .setOnlyAlertOnce(true)
                    .build()
            NotificationManagerCompat.from(context).notify(room.roomCode, NOTIFICATION_ID, notification)
        }

        companion object {
            private const val CHANNEL_ID = "sangat_live"
            private const val NOTIFICATION_ID = 0x5A46
        }
    }

/** A join asked for by a tap on the Sangat notification, held until a Sangat view model takes it. */
object SangatJoinRequests {
    const val ACTION_JOIN = "com.metrolist.music.action.JOIN_SANGAT"
    const val EXTRA_ROOM_CODE = "sangat_room_code"

    private val pending = MutableStateFlow<Pair<String, Long>?>(null)
    val requests: StateFlow<Pair<String, Long>?> = pending.asStateFlow()

    /**
     * Holds the join an incoming intent asks for, if it is a Sangat join; any other intent is left alone. True when the
     * intent was a Sangat join (even a blank one, which holds nothing), so the caller can bring the Home screen up, where
     * the join is taken.
     */
    fun offerFrom(
        intent: Intent,
        now: Long,
    ): Boolean {
        if (intent.action != ACTION_JOIN) return false
        intent.getStringExtra(EXTRA_ROOM_CODE)?.takeIf { it.isNotBlank() }?.let { pending.value = it to now }
        intent.action = null
        intent.removeExtra(EXTRA_ROOM_CODE)
        return true
    }

    /** Takes the held join, or null when none is held or it is too old to act on. */
    fun take(now: Long): String? {
        val held = pending.getAndUpdate { null } ?: return null
        return held.first.takeIf { SangatNotifyPolicy.isFreshJoin(held.second, now) }
    }
}

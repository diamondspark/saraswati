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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SangatNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val watcher: SangatLiveWatcher,
        private val dismissals: SangatDismissals,
        private val port: SangatRoomPort,
    ) {
        /** Posts a notification for each room the watcher announces. Called once, from the application scope. */
        fun start(scope: CoroutineScope) {
            scope.launch {
                watcher.carPrompts.collect { room ->
                    SangatNotifyPolicy.toNotify(listOf(room), dismissals.dismissed.value, port.currentRoomCode.first()).forEach(::post)
                }
            }
            scope.launch {
                combine(watcher.liveRooms, watcher.lastPolledAt, port.currentRoomCode, dismissals.dismissed) { live, polledAt, here, dismissed ->
                    // An empty list before the first poll means "not asked yet", not "no rooms".
                    if (polledAt == null) emptySet() else SangatNotifyPolicy.toCancel(postedRoomCodes(), live, here, dismissed)
                }.collect { codes -> codes.forEach { NotificationManagerCompat.from(context).cancel(it, NOTIFICATION_ID) } }
            }
        }

        /** The room codes of the Sangat notifications still in the shade, read back from the system. */
        private fun postedRoomCodes(): Set<String> =
            context
                .getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                .orEmpty()
                .filter { it.id == NOTIFICATION_ID }
                .mapNotNull { it.tag }
                .toSet()

        @SuppressLint("MissingPermission")
        private fun post(room: LiveSangatRoom) {
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

    /** Holds the join an incoming intent asks for, if it is a Sangat join; any other intent is left alone. */
    fun offerFrom(
        intent: Intent,
        now: Long,
    ) {
        if (intent.action != ACTION_JOIN) return
        intent.getStringExtra(EXTRA_ROOM_CODE)?.takeIf { it.isNotBlank() }?.let { pending.value = it to now }
        intent.action = null
        intent.removeExtra(EXTRA_ROOM_CODE)
    }

    /** Takes the held join, or null when none is held or it is too old to act on. */
    fun take(now: Long): String? {
        val held = pending.getAndUpdate { null } ?: return null
        return held.first.takeIf { SangatNotifyPolicy.isFreshJoin(held.second, now) }
    }
}

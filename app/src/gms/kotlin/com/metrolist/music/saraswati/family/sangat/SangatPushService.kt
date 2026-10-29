// SARASWATI: Sangat push (gms flavor). The hub sends a data-only {"type":"sangat_changed"} when a family Sangat
// starts, becomes a car room or ends. The message says nothing else: the handler asks the hub for the live list
// itself. Anything else is ignored. Both callbacks run off the main thread, and FCM gives about 20 seconds, so each
// waits for its work (at most 15 seconds) instead of handing it to a scope the service's destruction could cancel.
// The app never shows an FCM notification-type message: handleIntent drops any intent carrying notification keys
// before the SDK can display it, and the manifest turns notification delegation off, so only data messages get through.
package com.metrolist.music.saraswati.family.sangat

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class SangatPushService : FirebaseMessagingService() {
    @Inject lateinit var registrar: SangatPushRegistrar

    @Inject lateinit var handler: SangatPushHandler

    // SARASWATI: Sangat push — FirebaseMessagingService.handleIntent is public and not final in firebase-messaging 26.0.0.
    // Fails closed: an intent whose extras cannot be read is dropped too.
    override fun handleIntent(intent: Intent) {
        val dropped =
            try {
                val extras = intent.extras
                extras != null && SangatPushIntents.carriesNotification(extras.keySet(), extras.getString(SangatPushIntents.NOTIFICATION_ENABLED_KEY))
            } catch (e: Exception) {
                true
            }
        if (dropped) return
        super.handleIntent(intent)
    }

    override fun onNewToken(token: String) = waitFor { registrar.onNewToken(token) }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != TYPE_SANGAT_CHANGED) return
        waitFor { handler.onSangatChanged() }
    }

    // An exception out of a callback would take the app down; a failed push is only a missed notification.
    private fun waitFor(work: suspend () -> Unit) {
        try {
            runBlocking { withTimeoutOrNull(WORK_TIMEOUT_MS) { work() } }
        } catch (e: Exception) {
            // Nothing to do: the next poll or push puts the shade right.
        }
    }

    private companion object {
        const val TYPE_SANGAT_CHANGED = "sangat_changed"
        const val WORK_TIMEOUT_MS = 15_000L
    }
}

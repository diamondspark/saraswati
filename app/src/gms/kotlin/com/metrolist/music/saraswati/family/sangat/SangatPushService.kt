// SARASWATI: Sangat push skeleton (gms flavor only). Build plumbing only: the token registration and
// message handling logic lands in the next task.
package com.metrolist.music.saraswati.family.sangat

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SangatPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Unit

    override fun onMessageReceived(message: RemoteMessage) = Unit
}

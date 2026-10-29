/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — which incoming FCM intents the app refuses. The hub sends data messages only; a message
// carrying a notification payload would be shown by the SDK itself (before any app code decides), so the gms service
// drops such an intent before the SDK sees it. Kept free of Firebase and Android so it is unit tested.
package com.metrolist.music.saraswati.family.sangat

object SangatPushIntents {
    /** The extra FCM sets to "1" on a notification message. */
    const val NOTIFICATION_ENABLED_KEY = "gcm.n.e"

    private val NOTIFICATION_PREFIXES = listOf("gcm.n.", "gcm.notification.")

    /** True when the intent's extra keys ([keys], with [notificationFlag] the value of "gcm.n.e") mark a notification-type message. */
    fun carriesNotification(
        keys: Collection<String>,
        notificationFlag: String?,
    ): Boolean = notificationFlag == "1" || keys.any { key -> NOTIFICATION_PREFIXES.any(key::startsWith) }
}

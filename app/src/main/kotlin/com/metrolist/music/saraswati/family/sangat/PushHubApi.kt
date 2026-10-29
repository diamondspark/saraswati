/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — the two hub calls that tell the hub where to send the "a Sangat changed" message, as a
// small interface so the registration logic runs against a plain fake in JUnit. HubClient is the only implementation.
package com.metrolist.music.saraswati.family.sangat

interface PushHubApi {
    /** PUT /push/token: this phone's FCM token. 204 on success. */
    suspend fun registerPushToken(
        sessionToken: String,
        token: String,
    ): Result<Unit>

    /** DELETE /push/token: the hub forgets this phone's token. 204 on success. */
    suspend fun unregisterPushToken(sessionToken: String): Result<Unit>
}

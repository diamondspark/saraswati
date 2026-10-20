/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 17 — whole-queue Cast through the LAN relay. The relay runs only while a Cast session
// is up, only on Wi-Fi, only when the build flag and the owner's cast_relay switch are both on. Anything else
// returns null from [mediaItems], and the caller keeps upstream's five-item googlevideo window.
package com.metrolist.music.saraswati.castrelay

import androidx.media3.common.MediaItem
import com.metrolist.music.saraswati.SaraswatiFeatures
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom

class CastRelayController(
    private val random: SecureRandom,
    private val source: RelayAudioSource,
    private val storedMimeType: suspend (videoId: String) -> String?,
    private val featureEnabled: () -> Boolean,
    private val localAddress: () -> Inet4Address?,
    private val buildEnabled: Boolean = SaraswatiFeatures.CAST_RELAY,
    private val newServer: (InetAddress, RelaySession, RelayAudioSource) -> RelayHttpServer = { address, session, src ->
        RelayHttpServer(address, session, src)
    },
) {
    private class Running(
        val session: RelaySession,
        val server: RelayHttpServer,
    )

    private val lock = Any()
    private var running: Running? = null

    val isActive: Boolean
        get() = synchronized(lock) { running != null }

    /** Starts the relay for a new Cast session. True only when it is serving now. */
    fun start(): Boolean =
        synchronized(lock) {
            if (running != null) return true
            if (!buildEnabled || !featureEnabled()) return false
            val address = localAddress() ?: return false
            val session = RelaySession(random)
            val server = newServer(address, session, source)
            if (runCatching { server.start() }.isFailure) return false
            running = Running(session, server)
            true
        }

    fun stop() {
        synchronized(lock) {
            running?.server?.stop()
            running = null
        }
    }

    /** The phone lost Wi-Fi, so the relay address is gone with it. */
    fun onWifiLost() = stop()

    /**
     * Relay URIs for [queue] in order, capped at [CAST_QUEUE_MAX]. Null when the relay is not serving or a song
     * cannot be given a MIME type, which tells the caller to use the upstream path instead.
     */
    suspend fun mediaItems(queue: List<MediaItem>): List<MediaItem>? {
        val current = synchronized(lock) { running } ?: return null
        val capped = queue.take(CAST_QUEUE_MAX)
        val opaqueIds = current.session.register(capped.map { it.mediaId })
        val mimeTypes = HashMap<String, String>()
        for (videoId in opaqueIds.keys) {
            mimeTypes[videoId] = mimeTypeFor(videoId) ?: return null
        }
        return capped.map { item ->
            item
                .buildUpon()
                .setUri(current.server.urlFor(opaqueIds.getValue(item.mediaId)))
                .setMimeType(mimeTypes.getValue(item.mediaId))
                .build()
        }
    }

    private suspend fun mimeTypeFor(videoId: String): String? {
        storedMimeType(videoId)?.let { return it }
        // Unknown until the playback path has run once: opening the song runs it and stores the format.
        val stream = source.open(videoId, null) ?: return null
        stream.input.close()
        return stream.mimeType
    }

    companion object {
        const val CAST_QUEUE_MAX = 200
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 17 — whole-queue Cast through the LAN relay. The relay runs only while a Cast session
// is up, only on Wi-Fi, only when the build flag and the owner's cast_relay switch are both on. Anything else
// returns null from [mediaItems], and the caller keeps upstream's five-item googlevideo window.
package com.metrolist.music.saraswati.castrelay

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
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
            // Any failure to bring the relay up means "use upstream Cast", never a crash.
            val started =
                runCatching {
                    val address = localAddress() ?: return@runCatching null
                    val session = RelaySession(random)
                    val server = newServer(address, session, source)
                    server.start()
                    Running(session, server)
                }.getOrNull() ?: return false
            running = started
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

    /** Relay URIs for [queue] from its first item; see [relayQueue]. */
    suspend fun mediaItems(queue: List<MediaItem>): List<MediaItem>? = relayQueue(queue, 0)?.items

    /**
     * Relay URIs for [queue], capped at [CAST_QUEUE_MAX]. Null when the relay is not serving or the song at
     * [centerIndex] cannot be given a MIME type, which tells the caller to use the upstream path instead.
     *
     * A song whose format is already stored costs nothing. A song without one needs a live stream open (an
     * InnerTube /player call), so that is spent only on the current song and the next [RESOLVE_AHEAD]. Past
     * that window the queue stops at the first song with no stored format, and songs before the current one
     * are dropped up to the last such song; the caller's queue extension adds the rest one or two at a time
     * as playback nears the end of what was sent. [RelayQueue.startIndex] is where the current song landed.
     */
    suspend fun relayQueue(
        queue: List<MediaItem>,
        centerIndex: Int,
    ): RelayQueue? {
        val current = synchronized(lock) { running } ?: return null
        val capped = queue.take(CAST_QUEUE_MAX)
        if (centerIndex !in capped.indices) return null

        val mimeTypes = HashMap<String, String>()
        suspend fun mimeOf(videoId: String): String? =
            mimeTypes[videoId] ?: storedMimeTypeOrNull(videoId)?.also { mimeTypes[videoId] = it }

        var first = 0
        for (index in 0 until centerIndex) {
            if (mimeOf(capped[index].mediaId) == null) first = index + 1
        }
        mimeTypes[capped[centerIndex].mediaId] = mimeTypeFor(capped[centerIndex].mediaId) ?: return null

        var end = capped.size
        for (index in centerIndex + 1 until capped.size) {
            val videoId = capped[index].mediaId
            val inWindow = index - centerIndex <= RESOLVE_AHEAD
            val mime = mimeOf(videoId) ?: if (inWindow) mimeTypeFor(videoId)?.also { mimeTypes[videoId] = it } else null
            if (mime == null) {
                end = index
                break
            }
        }

        val kept = capped.subList(first, end)
        val opaqueIds = current.session.register(kept.map { it.mediaId })
        val items =
            kept.map { item ->
                item
                    .buildUpon()
                    .setUri(current.server.urlFor(opaqueIds.getValue(item.mediaId)))
                    .setMimeType(mimeTypes.getValue(item.mediaId))
                    .build()
            }
        return RelayQueue(items, centerIndex - first)
    }

    private suspend fun storedMimeTypeOrNull(videoId: String): String? =
        try {
            storedMimeType(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }

    private suspend fun mimeTypeFor(videoId: String): String? {
        storedMimeTypeOrNull(videoId)?.let { return it }
        // Unknown until the playback path has run once: opening the song runs it and stores the format. It throws
        // PlaybackException, not IOException, when the stream cannot be resolved; that is "cannot serve", not a crash.
        return try {
            val stream = source.open(videoId, null) ?: return null
            stream.input.close()
            stream.mimeType
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    class RelayQueue(
        val items: List<MediaItem>,
        val startIndex: Int,
    )

    companion object {
        const val CAST_QUEUE_MAX = 200
        const val RESOLVE_AHEAD = 10
    }
}

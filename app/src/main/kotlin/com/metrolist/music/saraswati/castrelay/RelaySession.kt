/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 16 — the per-Cast-session secrets for the LAN relay. The token gates every
// request, and each song gets an opaque id so the URL never carries a YouTube video id. Both are random
// per session, so a URL from an old session means nothing to a new one.
package com.metrolist.music.saraswati.castrelay

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class RelaySession(
    private val random: SecureRandom,
) {
    val token: String = randomHex(random, TOKEN_BYTES)

    private val videoToOpaque = ConcurrentHashMap<String, String>()
    private val opaqueToVideo = ConcurrentHashMap<String, String>()

    /** Maps each video id to its opaque id, reusing an id the session already issued for that video. */
    fun register(videoIds: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (videoId in videoIds.distinct()) {
            result[videoId] =
                videoToOpaque.computeIfAbsent(videoId) { newOpaqueFor(it) }
        }
        return result
    }

    fun resolve(opaqueId: String): String? = opaqueToVideo[opaqueId]

    private fun newOpaqueFor(videoId: String): String {
        while (true) {
            val opaque = randomHex(random, OPAQUE_BYTES)
            if (opaqueToVideo.putIfAbsent(opaque, videoId) == null) return opaque
        }
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val OPAQUE_BYTES = 16

        fun randomHex(
            random: SecureRandom,
            size: Int,
        ): String {
            val bytes = ByteArray(size)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

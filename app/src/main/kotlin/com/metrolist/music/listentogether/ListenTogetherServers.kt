/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.listentogether

import com.metrolist.music.BuildConfig
import com.metrolist.music.saraswati.family.HubClient
import kotlinx.serialization.Serializable

@Serializable
data class ListenTogetherServer(
    val name: String,
    val url: String,
    val location: String,
    val operator: String
)

object ListenTogetherServers {
    // SARASWATI: Phase 4 Task 9 — the one server this fork talks to is the family hub; its address is derived
    // from the build's hub URL (empty when the build has no hub or it is not https, which means "do not connect").
    val servers: List<ListenTogetherServer> by lazy {
        listOf(
            ListenTogetherServer(
                name = "Saraswati family hub",
                url = HubClient.sangatWebSocketUrl(BuildConfig.HUB_BASE_URL),
                location = "Family",
                operator = "Your family",
            ),
        )
    }

    val defaultServerUrl: String
        get() = servers.first().url

    fun findByUrl(url: String): ListenTogetherServer? = servers.firstOrNull { it.url == url }
}

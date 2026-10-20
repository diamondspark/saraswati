/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 16 — the relay only listens on Wi-Fi, so a phone on mobile data or a
// Wi-Fi-less network never exposes a port at all.
package com.metrolist.music.saraswati.castrelay

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object WifiAddress {
    fun current(cm: ConnectivityManager): Inet4Address? {
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val props = cm.getLinkProperties(network) ?: return null
        return props.linkAddresses
            .map { it.address }
            .firstNotNullOfOrNull { address ->
                (address as? Inet4Address)?.takeUnless { it.isLoopbackAddress || it.isLinkLocalAddress }
            }
    }
}

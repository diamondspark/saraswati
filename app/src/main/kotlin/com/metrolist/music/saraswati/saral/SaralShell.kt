/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — the one place that decides what MainActivity hosts and
// whether it still follows links, widgets, shares and recognition intents.
// While the cached role says grandparent, the answer to both is "Saral only":
// the navigation graph, and with it every route a link could name, is not
// composed at all, and an incoming intent is dropped instead of being held for
// later. SaralIsolationTest pins that MainActivity asks this object.
package com.metrolist.music.saraswati.saral

object SaralShell {
    enum class Content { SARAL, FULL_APP }

    fun contentFor(grandparent: Boolean): Content = if (grandparent) Content.SARAL else Content.FULL_APP

    fun routesIncomingIntents(grandparent: Boolean): Boolean = !grandparent
}

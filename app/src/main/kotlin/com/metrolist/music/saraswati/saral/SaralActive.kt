/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave A (M5) — whether Saral is the whole app right now, as a
// process-wide flag. MainActivity sets and clears it together with the cached
// role; CrashHandler (same process, no access to the activity) reads it and
// hands it to CrashActivity, which runs in its own process and so cannot read
// it directly. It exists so the crash screen can stay inside Saral: its Share
// button opens a chooser to any installed app, which a grandparent must not be
// able to do.
package com.metrolist.music.saraswati.saral

object SaralActive {
    @Volatile
    var on: Boolean = false
}

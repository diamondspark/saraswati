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
//
// Fix wave B2: App.onCreate also sets it, from the cached role, right after the
// crash handler is installed. MainActivity.onCreate was the only setter, so a
// crash in the application class, a service or a receiver (nothing has opened
// the activity yet) showed a Saral phone the trace and the Share button.
package com.metrolist.music.saraswati.saral

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.GrandparentRoleKey
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

object SaralActive {
    @Volatile
    var on: Boolean = false

    /**
     * Reads the cached role once and sets [on] from it: only a stored `true` turns it on; a key that is not
     * there (a phone that never joined) or is `false` leaves it off. A read that fails changes nothing, so
     * an error can never put a phone into Saral (the default stays off). Never throws.
     */
    suspend fun initFrom(dataStore: DataStore<Preferences>) {
        try {
            on = dataStore.data.first()[GrandparentRoleKey] == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unknown is not Saral: leave the flag as it is.
        }
    }
}

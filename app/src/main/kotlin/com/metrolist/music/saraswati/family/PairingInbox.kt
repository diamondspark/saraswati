/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — where a pairing link that arrived as a deep link waits
// for the join screen to pick it up. In memory only, and handed over once:
// the token never goes into a navigation route, where it would sit in the
// back stack and the saved instance state.
package com.metrolist.music.saraswati.family

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingInbox
    @Inject
    constructor() {
        @Volatile private var token: String? = null

        fun offer(validToken: String) {
            token = validToken
        }

        fun take(): String? {
            val taken = token
            token = null
            return taken
        }
    }

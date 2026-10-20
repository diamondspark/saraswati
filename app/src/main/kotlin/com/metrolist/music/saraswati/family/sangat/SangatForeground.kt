/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 review fix (M13) — whether the app is on screen, counted for the whole process. A second
// MainActivity can start before the first has stopped (a relaunch from a share or a deep link), and the
// first one's onStop must not stop the poll the second one is showing. The poll also runs on a scope of its
// own rather than on any one Activity's, so an Activity going away cannot take it with it. Never while this
// phone is Saral.
package com.metrolist.music.saraswati.family.sangat

import kotlinx.coroutines.CoroutineScope

class SangatForeground(
    private val watcher: SangatLiveWatcher,
    private val scope: CoroutineScope,
) {
    private var started = 0
    private var saral = false

    @Synchronized
    fun activityStarted() {
        started++
        sync()
    }

    @Synchronized
    fun activityStopped() {
        if (started > 0) started--
        sync()
    }

    @Synchronized
    fun setSaral(value: Boolean) {
        saral = value
        sync()
    }

    private fun sync() {
        if (started > 0 && !saral) {
            if (!watcher.running) watcher.start(scope)
        } else {
            watcher.stop()
        }
    }
}

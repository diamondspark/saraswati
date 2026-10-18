/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 (fix round 1, dedicated review) — the actual source of
// truth for "an install attempt is in flight and REQUEST_INSTALL_PACKAGES
// might still need to be turned off", written directly by
// InstallResultReceiver.onReceive, which runs regardless of whether
// UpdateActivity is resumed, paused, or not running at all.
//
// This exists because an in-memory, nulled-on-pause listener
// (InstallEvents) is NOT sufficient on its own: launching the mandatory
// system install-confirmation UI (STATUS_PENDING_USER_ACTION's EXTRA_INTENT,
// via FLAG_ACTIVITY_NEW_TASK) necessarily pauses UpdateActivity — this is
// the ORDINARY path for every real install (SessionParams.setRequireUserAction
// is set on API 31+, and a non-privileged installer always needs user
// confirmation below that too), not an edge case — so the real terminal
// broadcast (STATUS_SUCCESS/failure/cancel) arrives while
// InstallEvents.listener is null far more often than it arrives while
// live. UpdateActivity.onResume consults this every time, not only after a
// genuine process death, closing that gap for the common pause-without-death
// case as well as the crash case the original design already handled.
//
// Parameterized over SharedPreferences directly, not Context, specifically
// so PendingInstallTest can exercise every state transition (attempted →
// status recorded → consumed → cleared, and "attempted with no status yet"
// staying inert) with a hand-rolled in-memory fake, independent of the
// Activity lifecycle this bug lives in — no Robolectric/Context stub needed.
package dev.diamondspark.saraswati.updater

import android.content.Context
import android.content.SharedPreferences

object PendingInstall {
    private const val PREFS_NAME = "saraswati_updater"
    private const val KEY_ATTEMPTED = "pending_install_attempted"
    private const val KEY_STATUS = "pending_install_status"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Called right before a PackageInstaller session is ever opened — before any use of REQUEST_INSTALL_PACKAGES begins. */
    fun markAttemptStarted(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_ATTEMPTED, true).apply()
    }

    /** Called by InstallResultReceiver the instant a terminal status arrives, independent of any Activity's lifecycle. */
    fun recordTerminalStatus(prefs: SharedPreferences, status: Int) {
        prefs.edit().putInt(KEY_STATUS, status).apply()
    }

    /** True once an install attempt has begun and hasn't been fully resolved yet (see [clear]). */
    fun isAttemptPending(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_ATTEMPTED, false)

    /**
     * The terminal status recorded for the pending attempt, if one has
     * arrived yet — null while still waiting on the user inside the system
     * confirmation dialog. Consumes (clears) it so a later, unrelated
     * install attempt never sees a stale leftover value.
     */
    fun consumeTerminalStatus(prefs: SharedPreferences): Int? {
        if (!prefs.contains(KEY_STATUS)) return null
        val status = prefs.getInt(KEY_STATUS, 0)
        prefs.edit().remove(KEY_STATUS).apply()
        return status
    }

    /** Called once the attempt is fully resolved: the permission has been confirmed off. */
    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_ATTEMPTED).remove(KEY_STATUS).apply()
    }
}

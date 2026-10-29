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

data class PendingTarget(val packageName: String, val versionCode: Long, val versionName: String)

object PendingInstall {
    private const val PREFS_NAME = "saraswati_updater"
    private const val KEY_ATTEMPTED = "pending_install_attempted"
    private const val KEY_STATUS = "pending_install_status"
    private const val KEY_TARGET_PACKAGE = "pending_install_target_package"
    private const val KEY_TARGET_VERSION_CODE = "pending_install_target_version_code"
    private const val KEY_TARGET_VERSION_NAME = "pending_install_target_version_name"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Called right before a PackageInstaller session is ever opened — before any use of REQUEST_INSTALL_PACKAGES begins. */
    // SARASWATI: also records WHAT is being installed, so a relaunch that lost the
    // terminal status (the Updater's own self-update kills this process) can
    // tell "it succeeded" from "it didn't" by comparing installed versions.
    fun markAttemptStarted(prefs: SharedPreferences, target: PendingTarget) {
        prefs.edit()
            .putBoolean(KEY_ATTEMPTED, true)
            .putString(KEY_TARGET_PACKAGE, target.packageName)
            .putLong(KEY_TARGET_VERSION_CODE, target.versionCode)
            .putString(KEY_TARGET_VERSION_NAME, target.versionName)
            .apply()
    }

    /** The recorded target of the pending attempt; null if none, or if written by an older Updater build. */
    fun peekTarget(prefs: SharedPreferences): PendingTarget? {
        val pkg = prefs.getString(KEY_TARGET_PACKAGE, null) ?: return null
        val name = prefs.getString(KEY_TARGET_VERSION_NAME, null) ?: return null
        if (!prefs.contains(KEY_TARGET_VERSION_CODE)) return null
        return PendingTarget(pkg, prefs.getLong(KEY_TARGET_VERSION_CODE, 0L), name)
    }

    /** Called by InstallResultReceiver the instant a terminal status arrives, independent of any Activity's lifecycle. */
    fun recordTerminalStatus(prefs: SharedPreferences, status: Int) {
        prefs.edit().putInt(KEY_STATUS, status).apply()
    }

    /** True once an install attempt has begun and hasn't been fully resolved yet (see [clear]). */
    fun isAttemptPending(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_ATTEMPTED, false)

    /** Reads the terminal status without clearing it — see [decideInstallRecovery], which must not have side effects. */
    fun peekTerminalStatus(prefs: SharedPreferences): Int? {
        if (!prefs.contains(KEY_STATUS)) return null
        return prefs.getInt(KEY_STATUS, 0)
    }

    /**
     * The terminal status recorded for the pending attempt, if one has
     * arrived yet — null while still waiting on the user inside the system
     * confirmation dialog. Consumes (clears) it so a later, unrelated
     * install attempt never sees a stale leftover value.
     */
    fun consumeTerminalStatus(prefs: SharedPreferences): Int? {
        val status = peekTerminalStatus(prefs) ?: return null
        prefs.edit().remove(KEY_STATUS).apply()
        return status
    }

    /** Called once the attempt is fully resolved: the permission has been confirmed off. */
    fun clear(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_ATTEMPTED).remove(KEY_STATUS)
            .remove(KEY_TARGET_PACKAGE).remove(KEY_TARGET_VERSION_CODE).remove(KEY_TARGET_VERSION_NAME)
            .apply()
    }
}

/** What [UpdateActivity.recoverPendingInstallIfAny] should do, decided by [decideInstallRecovery]. */
sealed interface InstallRecoveryAction {
    /** Nothing to recover — let the caller's normal onResume flow continue untouched. */
    object None : InstallRecoveryAction

    /** [UiState.ReadyToInstall] survived (an ordinary pause+resume, not a crash) and the real outcome is now known. */
    data class DispatchStatus(val status: Int) : InstallRecoveryAction

    /** [UiState.ReadyToInstall] survived but the user is still inside the system confirmation dialog — nothing to do yet. */
    object WaitForStatus : InstallRecoveryAction

    /** Process death, and the recorded target is now installed: the install SUCCEEDED, so report Done (after the permission is off). */
    data class RecoverAsSucceeded(val versionName: String) : InstallRecoveryAction

    /**
     * Process death and the install did not (provably) complete: drop the
     * stale attempt and re-check from scratch. Never demands the permission
     * be switched off first; that is asked once, when nothing is left to install.
     */
    object Recheck : InstallRecoveryAction
}

// SARASWATI: Task 5 (fix round 2, dedicated re-review) — extracted as a
// pure function specifically so the exact condition that was wrong in fix
// round 1 is independently testable without Robolectric or a real
// Activity: telling a genuine crash (checkpoint reset all the way back to
// Checking) apart from completely ordinary pause+resume navigation that
// merely passes back through some OTHER checkpoint (most notably
// TurnPermissionOff, reached by tapping "Open settings" — that pauses and
// resumes this Activity exactly like the system install-confirmation UI
// does, but is not a crash). Fix round 1's fallback treated "anything that
// isn't ReadyToInstall" as a crash, which wrongly caught TurnPermissionOff
// too, discarding its real version and showing a misleading
// "Install didn't finish" instead of "Done on version X" for what was
// actually a successful update (self-correcting via Retry, never reopening
// the permission-left-on gap — not a repeat of the CRITICAL finding).
fun decideInstallRecovery(
    checkpoint: UiState,
    isAttemptPending: Boolean,
    knownStatus: Int?,
    target: PendingTarget? = null,
    installedTargetVersionCode: Long? = null,
): InstallRecoveryAction {
    if (!isAttemptPending) return InstallRecoveryAction.None
    return when {
        checkpoint is UiState.ReadyToInstall -> if (knownStatus != null) {
            InstallRecoveryAction.DispatchStatus(knownStatus)
        } else {
            InstallRecoveryAction.WaitForStatus
        }
        checkpoint == UiState.Checking ->
            if (target != null && installedTargetVersionCode != null && installedTargetVersionCode >= target.versionCode) {
                InstallRecoveryAction.RecoverAsSucceeded(target.versionName)
            } else {
                InstallRecoveryAction.Recheck
            }
        else -> InstallRecoveryAction.None
    }
}

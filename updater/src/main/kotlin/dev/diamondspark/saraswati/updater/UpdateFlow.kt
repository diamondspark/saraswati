/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 — the Updater's whole state machine, deliberately
// isolated from Android (no import of anything in android.*) so it is
// exercised by a plain JVM unit test (UpdateFlowTest), no Robolectric/
// emulator needed, same reasoning as :update-core.
//
// [reduce] is pure: given the same three inputs it always returns the same
// [UiState]. The one piece of "ambient" information it needs — whether
// REQUEST_INSTALL_PACKAGES is granted RIGHT NOW — is never read from a
// field or a singleton; it is always the caller-supplied [canInstallNow]
// parameter, re-queried from PackageManager by UpdateActivity immediately
// before every single dispatch. That is what makes the guard at the top of
// this function correct: it is not asking "was permission on when this
// install attempt started", it is asking "is permission on at this exact
// instant", every single time reduce runs, for as long as the state is
// [UiState.TurnPermissionOff] — which is Review Focus item 2's named risk
// (the install permission left on after success, failure, cancel, or a
// crash mid-flow): there is no event type this guard depends on, so it
// fires no matter what triggers the next dispatch — a user tapping
// something, the activity's onResume re-checking after Settings, or (see
// UpdateActivity's persisted-pending-flag recovery) the very first dispatch
// after the process was killed and relaunched mid-flow.
//
// [UiState.Downloading] and [UiState.Installing] are intentionally the two
// states with the least payload (just a percent, or nothing) — they exist
// purely so the UI has something to render while a long-running operation
// is in flight. Neither is a valid predecessor [state] for
// [Event.DownloadFinished] or [Event.InstallStatus] in this function: the
// manifest those terminal events need is still sitting in the *real*
// checkpoint state — [UiState.UpdateReady] (for the download) or
// [UiState.ReadyToInstall] (for the install) — because UpdateActivity never
// advances its checkpoint through the ephemeral progress states; it only
// *renders* them. See UpdateActivity's `dispatch` for exactly how it keeps
// those two notions (what's checkpointed vs. what's momentarily on screen)
// apart.
package dev.diamondspark.saraswati.updater

import dev.diamondspark.saraswati.updatecore.Decision
import dev.diamondspark.saraswati.updatecore.UpdateDecider
import dev.diamondspark.saraswati.updatecore.UpdateManifest
import dev.diamondspark.saraswati.updatecore.VerifyResult

sealed interface UiState {
    object Checking : UiState
    data class UpToDate(val installed: Long) : UiState
    data class AlreadyAhead(val installed: Long) : UiState
    data class UpdateReady(val manifest: UpdateManifest) : UiState
    data class Downloading(val percent: Int) : UiState
    data class NeedsInstallPermission(val manifest: UpdateManifest) : UiState
    data class ReadyToInstall(val manifest: UpdateManifest) : UiState
    object Installing : UiState
    // SARASWATI: [after] is where the flow lands once the permission is off
    // when there is no [version] to report as Done (e.g. the UpToDate that
    // was showing when the permission turned out to be left on); null keeps
    // the original failed/cancelled-install behaviour (retryable Error).
    data class TurnPermissionOff(val version: String?, val after: UiState? = null) : UiState
    data class Done(val version: String) : UiState
    data class Error(val message: String, val canRetry: Boolean) : UiState
}

sealed interface Event {
    // SARASWATI: [followUpQueued] — the caller knows another install (the
    // Updater's own self-update) is still to come in this run, so the
    // permission must stay on: no TurnPermissionOff until the last install.
    data class ManifestLoaded(val manifest: UpdateManifest, val installed: Long?, val followUpQueued: Boolean = false) : Event
    data class ManifestFailed(val reason: String) : Event
    data class DownloadProgress(val percent: Int) : Event
    data class DownloadFinished(val verified: VerifyResult) : Event
    object UserTappedUpdate : Event
    data class PermissionChecked(val canInstall: Boolean) : Event
    data class InstallStatus(val code: Int, val followUpQueued: Boolean = false) : Event
    object Retry : Event
}

// Mirrors android.content.pm.PackageInstaller's real int constants (this
// file imports nothing from android.* on purpose — see the file header).
// InstallResultReceiver passes PackageInstaller's own EXTRA_STATUS value
// straight through as Event.InstallStatus(code) with no translation, so
// these numbers are not a private encoding of this module's choosing: they
// are PackageInstaller's.
private const val INSTALL_STATUS_SUCCESS = 0

/** The message shown once the permission is off after an attempt that didn't succeed. */
private const val INSTALL_NOT_FINISHED_MESSAGE = "Install didn't finish. You can try again."

fun reduce(state: UiState, event: Event, canInstallNow: Boolean): UiState {
    // Universal guard, checked before anything else and regardless of the
    // event: once showing TurnPermissionOff, stay there until the
    // permission is confirmed off. See the file header for why this must
    // not depend on which event arrives.
    if (state is UiState.TurnPermissionOff) {
        if (canInstallNow) return state
        return state.after
            ?: if (state.version != null) {
                UiState.Done(state.version)
            } else {
                UiState.Error(INSTALL_NOT_FINISHED_MESSAGE, canRetry = true)
            }
    }

    return when (event) {
        // SARASWATI: fix round 1, dedicated review (MINOR) — gated on the
        // predecessor state like every other branch below, instead of
        // firing unconditionally regardless of `state`. Not reachable as a
        // live bug given how UpdateActivity drives dispatch today (a
        // manifest fetch only ever starts from Checking), but kept
        // consistent for defense in depth rather than being the one
        // exception to the pattern.
        is Event.ManifestLoaded -> when (state) {
            UiState.Checking -> when (val decision = UpdateDecider.decide(event.installed, event.manifest)) {
                is Decision.UpToDate ->
                    endOfRun(UiState.UpToDate(event.installed ?: event.manifest.versionCode), canInstallNow, event.followUpQueued)
                is Decision.AlreadyAhead -> endOfRun(UiState.AlreadyAhead(decision.installed), canInstallNow, event.followUpQueued)
                is Decision.UpdateAvailable -> UiState.UpdateReady(decision.manifest)
                is Decision.Rejected -> UiState.Error(decision.reason, canRetry = true)
            }
            else -> state
        }

        is Event.ManifestFailed -> when (state) {
            UiState.Checking -> UiState.Error(event.reason, canRetry = true)
            else -> state
        }

        is Event.DownloadProgress -> when (state) {
            is UiState.UpdateReady, is UiState.Downloading -> UiState.Downloading(event.percent)
            else -> state
        }

        is Event.DownloadFinished -> when (state) {
            is UiState.UpdateReady -> when (val verified = event.verified) {
                VerifyResult.Ok -> if (canInstallNow) {
                    UiState.ReadyToInstall(state.manifest)
                } else {
                    UiState.NeedsInstallPermission(state.manifest)
                }
                is VerifyResult.Failed -> endOfRun(UiState.Error(verified.reason, canRetry = false), canInstallNow, followUpQueued = false)
            }
            else -> state
        }

        Event.UserTappedUpdate -> when (state) {
            is UiState.UpdateReady -> UiState.Downloading(0)
            is UiState.ReadyToInstall -> UiState.Installing
            else -> state
        }

        is Event.PermissionChecked -> when (state) {
            is UiState.NeedsInstallPermission -> if (event.canInstall) {
                UiState.ReadyToInstall(state.manifest)
            } else {
                state
            }
            else -> state
        }

        is Event.InstallStatus -> when (state) {
            is UiState.ReadyToInstall -> {
                val version = if (event.code == INSTALL_STATUS_SUCCESS) state.manifest.versionName else null
                // SARASWATI: a successful install with another one still queued
                // keeps the permission on (Done is transient: the caller moves
                // straight on to the queued install).
                if (version != null && event.followUpQueued) UiState.Done(version) else UiState.TurnPermissionOff(version)
            }
            else -> state
        }

        Event.Retry -> when (state) {
            is UiState.Error -> UiState.Checking
            else -> state
        }
    }
}

// SARASWATI: invariant — a run that ends with nothing left to install while
// the permission is still on asks, once, for it to be switched off. Never
// when another install is queued, so "off" is never followed by "on" again.
private fun endOfRun(terminal: UiState, canInstallNow: Boolean, followUpQueued: Boolean): UiState =
    if (canInstallNow && !followUpQueued) UiState.TurnPermissionOff(version = null, after = terminal) else terminal

/** What UpdateActivity does when a new Intent reaches its already-running instance. */
enum class NewIntentAction {
    /** Start over from [UiState.Checking], exactly like a fresh launch with that Intent. */
    Recheck,

    /** Keep the current flow; the new Intent's channel applies to the next check. */
    KeepCurrentFlow,
}

/**
 * SARASWATI: final-review fix round 2 (R2). UpdateActivity is
 * `launchMode="singleTask"`, so a second banner tap (possibly with a
 * different `channel` extra) lands in `onNewIntent` on the instance that is
 * already running, not in a fresh `onCreate`. From a resting state — still
 * checking, or showing a result nothing has acted on yet — that tap must
 * behave like a fresh launch: re-check against the new Intent's channel.
 *
 * Mid-install it must not: a pending PackageInstaller attempt, a download or
 * install in progress, a verified APK waiting to install, or a
 * permission prompt (above all [UiState.TurnPermissionOff], which exists to
 * get REQUEST_INSTALL_PACKAGES switched back off) all keep their flow. The
 * new channel still takes effect on the next check (UpdateActivity always
 * adopts the new Intent).
 */
fun decideOnNewIntent(checkpoint: UiState, displayed: UiState, isInstallAttemptPending: Boolean): NewIntentAction {
    if (isInstallAttemptPending) return NewIntentAction.KeepCurrentFlow
    if (displayed is UiState.Downloading || displayed == UiState.Installing) return NewIntentAction.KeepCurrentFlow
    return when (checkpoint) {
        is UiState.NeedsInstallPermission,
        is UiState.ReadyToInstall,
        UiState.Installing,
        is UiState.Downloading,
        is UiState.TurnPermissionOff,
        -> NewIntentAction.KeepCurrentFlow

        UiState.Checking,
        is UiState.UpToDate,
        is UiState.AlreadyAhead,
        is UiState.UpdateReady,
        is UiState.Done,
        is UiState.Error,
        -> NewIntentAction.Recheck
    }
}

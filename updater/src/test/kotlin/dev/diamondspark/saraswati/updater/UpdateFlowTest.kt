/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 — reducer tests, written before `reduce` existed
// (red), now green. Review Focus item 2 names this file's TurnPermissionOff
// coverage as the single highest-stakes behavior in the whole Updater: the
// install permission must never stay on after success, failure, cancel, or
// (via UpdateActivity's separate persisted-flag recovery, not testable here
// since reduce has no persistence) a crash mid-flow.
package dev.diamondspark.saraswati.updater

import dev.diamondspark.saraswati.updatecore.ApkAsset
import dev.diamondspark.saraswati.updatecore.UpdateManifest
import dev.diamondspark.saraswati.updatecore.VerifyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFlowTest {

    private fun manifest(versionCode: Long = 42L, versionName: String = "1.42"): UpdateManifest = UpdateManifest(
        schema = 1,
        channel = "stable",
        versionCode = versionCode,
        versionName = versionName,
        apk = ApkAsset(
            url = "https://github.com/diamondspark/saraswati/releases/download/v$versionCode/saraswati.apk",
            sha256 = "a".repeat(64),
            size = 1_000_000L,
        ),
        urgent = false,
        notes = "",
        publishedAt = "2026-10-17T00:00:00Z",
    )

    @Test
    fun `manifest newer than installed becomes UpdateReady`() {
        val m = manifest(versionCode = 10)
        val result = reduce(UiState.Checking, Event.ManifestLoaded(m, installed = 5L), canInstallNow = false)
        assertEquals(UiState.UpdateReady(m), result)
    }

    @Test
    fun `manifest equal becomes UpToDate`() {
        val m = manifest(versionCode = 10)
        val result = reduce(UiState.Checking, Event.ManifestLoaded(m, installed = 10L), canInstallNow = false)
        assertEquals(UiState.UpToDate(10L), result)
    }

    @Test
    fun `manifest older becomes AlreadyAhead`() {
        val m = manifest(versionCode = 5)
        val result = reduce(UiState.Checking, Event.ManifestLoaded(m, installed = 10L), canInstallNow = false)
        assertEquals(UiState.AlreadyAhead(10L), result)
    }

    @Test
    fun `verification failure becomes a non-retryable Error and never reaches Installing`() {
        val m = manifest()
        val result = reduce(
            UiState.UpdateReady(m),
            Event.DownloadFinished(VerifyResult.Failed("sha256 mismatch")),
            canInstallNow = false,
        )
        assertEquals(UiState.Error("sha256 mismatch", canRetry = false), result)
        assertNotEquals(UiState.Installing, result)
    }

    @Test
    fun `missing install permission becomes NeedsInstallPermission`() {
        val m = manifest()
        val result = reduce(
            UiState.UpdateReady(m),
            Event.DownloadFinished(VerifyResult.Ok),
            canInstallNow = false,
        )
        assertEquals(UiState.NeedsInstallPermission(m), result)
    }

    @Test
    fun `download finished ok with permission already granted becomes ReadyToInstall`() {
        val m = manifest()
        val result = reduce(
            UiState.UpdateReady(m),
            Event.DownloadFinished(VerifyResult.Ok),
            canInstallNow = true,
        )
        assertEquals(UiState.ReadyToInstall(m), result)
    }

    @Test
    fun `granted permission becomes ReadyToInstall`() {
        val m = manifest()
        val result = reduce(
            UiState.NeedsInstallPermission(m),
            Event.PermissionChecked(canInstall = true),
            canInstallNow = true,
        )
        assertEquals(UiState.ReadyToInstall(m), result)
    }

    @Test
    fun `install success while the permission is still on becomes TurnPermissionOff`() {
        val m = manifest(versionName = "1.42")
        val result = reduce(
            UiState.ReadyToInstall(m),
            Event.InstallStatus(code = 0), // PackageInstaller.STATUS_SUCCESS
            canInstallNow = true,
        )
        assertEquals(UiState.TurnPermissionOff("1.42"), result)
    }

    @Test
    fun `install cancel while the permission is still on becomes TurnPermissionOff`() {
        val m = manifest()
        val result = reduce(
            UiState.ReadyToInstall(m),
            Event.InstallStatus(code = 2), // PackageInstaller.STATUS_FAILURE_ABORTED
            canInstallNow = true,
        )
        assertEquals(UiState.TurnPermissionOff(null), result)
    }

    @Test
    fun `install failure while the permission is on becomes TurnPermissionOff`() {
        val m = manifest()
        val result = reduce(
            UiState.ReadyToInstall(m),
            Event.InstallStatus(code = 6), // PackageInstaller.STATUS_FAILURE_INVALID
            canInstallNow = true,
        )
        assertEquals(UiState.TurnPermissionOff(null), result)
    }

    // SARASWATI: permission-dance fix — TurnPermissionOff only after the LAST install of a run.

    @Test
    fun `app install success with an updater follow-up queued keeps the permission on`() {
        val m = manifest(versionName = "1.42")
        val result = reduce(
            UiState.ReadyToInstall(m),
            Event.InstallStatus(code = 0, followUpQueued = true),
            canInstallNow = true,
        )
        assertEquals(UiState.Done("1.42"), result)
    }

    @Test
    fun `app install failure with a follow-up queued still asks to turn the permission off`() {
        val result = reduce(
            UiState.ReadyToInstall(manifest()),
            Event.InstallStatus(code = 2, followUpQueued = true),
            canInstallNow = true,
        )
        assertEquals(UiState.TurnPermissionOff(null), result)
    }

    @Test
    fun `full run - app then self-update - asks to turn the permission off exactly once, at the end`() {
        val app = manifest(versionCode = 42, versionName = "1.42")
        val self = manifest(versionCode = 7, versionName = "7")
        val seen = mutableListOf<UiState>()
        var s: UiState = UiState.ReadyToInstall(app)
        s = reduce(s, Event.InstallStatus(0, followUpQueued = true), true).also { seen += it }
        // Activity moves on to the self-update with the permission still on.
        s = reduce(UiState.Checking, Event.ManifestLoaded(self, installed = 6L), true).also { seen += it }
        s = reduce(s, Event.UserTappedUpdate, true).also { seen += it }
        s = reduce(UiState.UpdateReady(self), Event.DownloadFinished(VerifyResult.Ok), true).also { seen += it }
        assertEquals(UiState.ReadyToInstall(self), s)
        assertTrue(seen.none { it is UiState.TurnPermissionOff || it is UiState.NeedsInstallPermission })
        // Self-install finished (process killed -> recovery path): now, once.
        assertEquals(UiState.Done("7"), reduce(UiState.TurnPermissionOff("7"), Event.PermissionChecked(false), false))
    }

    @Test
    fun `relaunch after a successful self-install shows TurnPermissionOff then Done, no error`() {
        val start = UiState.TurnPermissionOff("7")
        assertEquals(start, reduce(start, Event.PermissionChecked(true), canInstallNow = true))
        assertEquals(UiState.Done("7"), reduce(start, Event.PermissionChecked(false), canInstallNow = false))
    }

    @Test
    fun `really failed attempt with permission on re-checks and goes straight to ReadyToInstall`() {
        val m = manifest(versionCode = 10)
        val update = reduce(UiState.Checking, Event.ManifestLoaded(m, installed = 5L), canInstallNow = true)
        assertEquals(UiState.UpdateReady(m), update)
        assertEquals(UiState.ReadyToInstall(m), reduce(update, Event.DownloadFinished(VerifyResult.Ok), canInstallNow = true))
    }

    @Test
    fun `up to date with the permission on asks to turn it off once, then lands on UpToDate`() {
        val m = manifest(versionCode = 10)
        val ask = reduce(UiState.Checking, Event.ManifestLoaded(m, installed = 10L), canInstallNow = true)
        assertEquals(UiState.TurnPermissionOff(null, after = UiState.UpToDate(10L)), ask)
        assertEquals(ask, reduce(ask, Event.Retry, canInstallNow = true))
        assertEquals(UiState.UpToDate(10L), reduce(ask, Event.PermissionChecked(false), canInstallNow = false))
    }

    @Test
    fun `already ahead with the permission on asks to turn it off once`() {
        val m = manifest(versionCode = 5)
        val ask = reduce(UiState.Checking, Event.ManifestLoaded(m, installed = 10L), canInstallNow = true)
        assertEquals(UiState.TurnPermissionOff(null, after = UiState.AlreadyAhead(10L)), ask)
    }

    @Test
    fun `up to date with a self-update queued does not ask to turn the permission off`() {
        val m = manifest(versionCode = 10)
        val result = reduce(
            UiState.Checking,
            Event.ManifestLoaded(m, installed = 10L, followUpQueued = true),
            canInstallNow = true,
        )
        assertEquals(UiState.UpToDate(10L), result)
    }

    @Test
    fun `non-retryable error with the permission on asks to turn it off, then shows the error`() {
        val err = UiState.Error("sha256 mismatch", canRetry = false)
        val ask = reduce(
            UiState.UpdateReady(manifest()),
            Event.DownloadFinished(VerifyResult.Failed("sha256 mismatch")),
            canInstallNow = true,
        )
        assertEquals(UiState.TurnPermissionOff(null, after = err), ask)
        assertEquals(err, reduce(ask, Event.PermissionChecked(false), canInstallNow = false))
    }

    @Test
    fun `TurnPermissionOff stays until the permission is off`() {
        val pending = UiState.TurnPermissionOff("1.42")
        // Any event, as long as the permission is still on: no change.
        assertEquals(pending, reduce(pending, Event.Retry, canInstallNow = true))
        assertEquals(pending, reduce(pending, Event.PermissionChecked(canInstall = true), canInstallNow = true))
        assertEquals(pending, reduce(pending, Event.UserTappedUpdate, canInstallNow = true))

        val pendingNoVersion = UiState.TurnPermissionOff(null)
        assertEquals(pendingNoVersion, reduce(pendingNoVersion, Event.Retry, canInstallNow = true))
    }

    @Test
    fun `permission off after success becomes Done`() {
        val result = reduce(
            UiState.TurnPermissionOff("1.42"),
            Event.PermissionChecked(canInstall = false),
            canInstallNow = false,
        )
        assertEquals(UiState.Done("1.42"), result)
    }

    @Test
    fun `permission off after a failed or cancelled install becomes a retryable Error`() {
        val result = reduce(
            UiState.TurnPermissionOff(null),
            Event.PermissionChecked(canInstall = false),
            canInstallNow = false,
        )
        assertEquals(UiState.Error("Install didn't finish. You can try again.", canRetry = true), result)
        assertTrue((result as UiState.Error).canRetry)
    }

    @Test
    fun `network failure becomes a retryable Error`() {
        val result = reduce(UiState.Checking, Event.ManifestFailed("offline"), canInstallNow = false)
        assertEquals(UiState.Error("offline", canRetry = true), result)
        assertTrue((result as UiState.Error).canRetry)
    }

    @Test
    fun `download progress updates the percent while downloading`() {
        val m = manifest()
        val afterStart = reduce(UiState.UpdateReady(m), Event.UserTappedUpdate, canInstallNow = true)
        assertEquals(UiState.Downloading(0), afterStart)
        val afterProgress = reduce(afterStart, Event.DownloadProgress(37), canInstallNow = true)
        assertEquals(UiState.Downloading(37), afterProgress)
    }

    @Test
    fun `retry from error restarts checking`() {
        val result = reduce(UiState.Error("offline", canRetry = true), Event.Retry, canInstallNow = false)
        assertEquals(UiState.Checking, result)
    }

    @Test
    fun `tapping update to install transitions ReadyToInstall to Installing`() {
        val m = manifest()
        val result = reduce(UiState.ReadyToInstall(m), Event.UserTappedUpdate, canInstallNow = true)
        assertEquals(UiState.Installing, result)
    }

    // SARASWATI: fix round 1, dedicated review (MINOR) — ManifestLoaded/
    // ManifestFailed are now gated on the predecessor state like every
    // other event in reduce, instead of firing unconditionally.

    @Test
    fun `manifest loaded is ignored outside the Checking state`() {
        val m = manifest()
        val mid = UiState.Downloading(50)
        val result = reduce(mid, Event.ManifestLoaded(m, installed = 1L), canInstallNow = false)
        assertEquals(mid, result)
    }

    @Test
    fun `manifest failed is ignored outside the Checking state`() {
        val mid = UiState.Installing
        val result = reduce(mid, Event.ManifestFailed("offline"), canInstallNow = false)
        assertEquals(mid, result)
    }

    // ---- decideOnNewIntent (final-review fix round 2, R2) ---------------

    @Test
    fun `a new intent while resting re-checks like a fresh launch`() {
        val m = manifest()
        val resting = listOf(
            UiState.Checking,
            UiState.UpToDate(42L),
            UiState.AlreadyAhead(43L),
            UiState.UpdateReady(m),
            UiState.Done("1.42"),
            UiState.Error("offline", canRetry = true),
            UiState.Error("bad signature", canRetry = false),
        )
        for (state in resting) {
            assertEquals(
                "state $state",
                NewIntentAction.Recheck,
                decideOnNewIntent(checkpoint = state, displayed = state, isInstallAttemptPending = false),
            )
        }
    }

    @Test
    fun `a new intent mid-install keeps the current flow`() {
        val m = manifest()
        val midFlow = listOf(
            UiState.NeedsInstallPermission(m),
            UiState.ReadyToInstall(m),
            UiState.TurnPermissionOff("1.42"),
            UiState.TurnPermissionOff(null),
        )
        for (state in midFlow) {
            assertEquals(
                "state $state",
                NewIntentAction.KeepCurrentFlow,
                decideOnNewIntent(checkpoint = state, displayed = state, isInstallAttemptPending = false),
            )
        }
    }

    @Test
    fun `a new intent while downloading or installing keeps the current flow`() {
        // UpdateActivity pins checkpoint at the manifest-bearing state while
        // displayed shows progress; displayed alone must be enough to hold.
        val m = manifest()
        assertEquals(
            NewIntentAction.KeepCurrentFlow,
            decideOnNewIntent(UiState.UpdateReady(m), UiState.Downloading(37), isInstallAttemptPending = false),
        )
        assertEquals(
            NewIntentAction.KeepCurrentFlow,
            decideOnNewIntent(UiState.ReadyToInstall(m), UiState.Installing, isInstallAttemptPending = false),
        )
    }

    @Test
    fun `a pending install attempt always keeps the current flow`() {
        // e.g. the system install dialog is on top: onResume's recovery owns this.
        assertEquals(
            NewIntentAction.KeepCurrentFlow,
            decideOnNewIntent(UiState.UpToDate(42L), UiState.UpToDate(42L), isInstallAttemptPending = true),
        )
        assertEquals(
            NewIntentAction.KeepCurrentFlow,
            decideOnNewIntent(UiState.Checking, UiState.Checking, isInstallAttemptPending = true),
        )
    }
}

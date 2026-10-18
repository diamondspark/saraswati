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
            canInstallNow = true,
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
}

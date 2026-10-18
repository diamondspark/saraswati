/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — the one invariant that matters most here is "never
// downgrade": a manifest claiming an older versionCode than what's installed
// must never come back as an update to install.
package dev.diamondspark.saraswati.updatecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun manifestWithVersion(versionCode: Long): UpdateManifest = UpdateManifest(
    schema = 1,
    channel = "stable",
    versionCode = versionCode,
    versionName = "v$versionCode",
    apk = ApkAsset(
        url = RELEASE_URL_PREFIX + "v$versionCode/saraswati.apk",
        sha256 = "a".repeat(64),
        size = 1_000_000L,
    ),
    urgent = false,
    notes = "",
    publishedAt = "2026-10-17T00:00:00Z",
    updater = null,
)

class UpdateDeciderTest {

    @Test
    fun `not installed offers the update`() {
        val manifest = manifestWithVersion(100)
        val decision = UpdateDecider.decide(installedVersionCode = null, manifest = manifest)
        assertTrue(decision is Decision.UpdateAvailable)
        assertEquals(manifest, (decision as Decision.UpdateAvailable).manifest)
    }

    @Test
    fun `equal version is up to date`() {
        val manifest = manifestWithVersion(100)
        val decision = UpdateDecider.decide(installedVersionCode = 100, manifest = manifest)
        assertTrue(decision is Decision.UpToDate)
    }

    @Test
    fun `lower manifest version reports already ahead and never downgrades`() {
        val manifest = manifestWithVersion(50)
        val decision = UpdateDecider.decide(installedVersionCode = 100, manifest = manifest)
        assertTrue(decision is Decision.AlreadyAhead)
        assertEquals(100L, (decision as Decision.AlreadyAhead).installed)
    }

    @Test
    fun `higher version offers the update`() {
        val manifest = manifestWithVersion(150)
        val decision = UpdateDecider.decide(installedVersionCode = 100, manifest = manifest)
        assertTrue(decision is Decision.UpdateAvailable)
        assertEquals(manifest, (decision as Decision.UpdateAvailable).manifest)
    }
}

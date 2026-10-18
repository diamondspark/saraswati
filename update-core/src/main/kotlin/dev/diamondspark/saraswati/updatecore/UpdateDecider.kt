/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — the one rule that matters here: never offer a
// manifest whose versionCode is lower than what's already installed. A
// stale or rolled-back manifest (served by a misconfigured mirror, or a
// replay of an old response) must read as "you're already ahead", not as
// an update to install.
package dev.diamondspark.saraswati.updatecore

sealed interface Decision {
    object UpToDate : Decision
    data class AlreadyAhead(val installed: Long) : Decision
    data class UpdateAvailable(val manifest: UpdateManifest) : Decision
    data class Rejected(val reason: String) : Decision
}

object UpdateDecider {
    fun decide(installedVersionCode: Long?, manifest: UpdateManifest): Decision {
        if (installedVersionCode == null) {
            return Decision.UpdateAvailable(manifest)
        }
        return when {
            manifest.versionCode == installedVersionCode -> Decision.UpToDate
            manifest.versionCode < installedVersionCode -> Decision.AlreadyAhead(installedVersionCode)
            else -> Decision.UpdateAvailable(manifest)
        }
    }
}

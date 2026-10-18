/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — manifest schema. `updater` is optional (a manifest
// published before the installer app existed, or one that simply has no
// updater-app change to ship, omits it entirely), so it carries a default
// of null to let kotlinx.serialization accept the key being absent.
package dev.diamondspark.saraswati.updatecore

import kotlinx.serialization.Serializable

@Serializable
data class UpdaterAsset(
    val versionCode: Long,
    val apk: ApkAsset,
)

@Serializable
data class UpdateManifest(
    val schema: Int,
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val apk: ApkAsset,
    val urgent: Boolean,
    val notes: String,
    val publishedAt: String,
    val updater: UpdaterAsset? = null,
)

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: MediaSession.ControllerInfo.packageName is the package name a
// controller CLAIMED when it connected — Media3 does not always verify
// that name against the uid that actually did the binding (audit NEW-4).
// Without this check, any app could claim the package name
// "com.google.android.projection.gearhead" and ride straight through
// ControllerPolicy's Android Auto signing-digest allowlist as if it
// really were Android Auto, because the digest lookup is keyed by that
// same (unverified) package name. CallerInfoFactory.create() closes that
// hole: it asks PackageManager which package names actually belong to the
// connecting uid and returns null — so ControllerPolicy denies — unless
// the claimed name is one of them. Only once that holds does it look up
// the real signing certificates and system-app flag for that name.
package com.metrolist.music.saraswati.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** PackageManager access, abstracted so [CallerInfoFactory] can be tested with a fake. */
interface PackageLookup {
    /** Every package name PackageManager has on record for [uid], or empty if none/unknown. */
    fun packagesForUid(uid: Int): List<String>

    /** SHA-256 digests (lowercase hex) of every signing certificate [packageName] presents, including lineage. */
    fun signingSha256(
        uid: Int,
        packageName: String,
    ): Set<String>

    fun isSystemApp(packageName: String): Boolean
}

object CallerInfoFactory {
    /**
     * Returns null (so [ControllerPolicy.isAllowed] denies) unless [controllerPackage] is among
     * [PackageLookup.packagesForUid] for [controllerUid] — i.e. unless the claimed package name
     * actually belongs to the uid that connected.
     */
    fun create(
        controllerPackage: String,
        controllerUid: Int,
        isTrusted: Boolean,
        lookup: PackageLookup,
    ): CallerInfo? {
        if (controllerPackage !in lookup.packagesForUid(controllerUid)) return null
        return CallerInfo(
            packageName = controllerPackage,
            isTrusted = isTrusted,
            isSystemApp = lookup.isSystemApp(controllerPackage),
            signingSha256 = lookup.signingSha256(controllerUid, controllerPackage),
        )
    }
}

/** [PackageLookup] backed by the real [PackageManager]. */
class AndroidPackageLookup(
    private val context: Context,
) : PackageLookup {
    private val packageManager get() = context.packageManager

    override fun packagesForUid(uid: Int): List<String> = packageManager.getPackagesForUid(uid)?.toList().orEmpty()

    override fun signingSha256(
        uid: Int,
        packageName: String,
    ): Set<String> =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo =
                    packageManager
                        .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo
                val certificates =
                    when {
                        signingInfo == null -> emptyArray()
                        // Multiple current signers have no rotation lineage to fall back to.
                        signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                        // Single signer: accept the current certificate and every past one in its
                        // rotation lineage, per the brief (R4/step 3).
                        else -> signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
                    }
                certificates.map { it.toByteArray().sha256Hex() }.toSet()
            } else {
                @Suppress("DEPRECATION")
                packageManager
                    .getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    .signatures
                    ?.map { it.toByteArray().sha256Hex() }
                    ?.toSet()
                    .orEmpty()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            emptySet()
        }

    override fun isSystemApp(packageName: String): Boolean =
        try {
            val flags = packageManager.getApplicationInfo(packageName, 0).flags
            (flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}

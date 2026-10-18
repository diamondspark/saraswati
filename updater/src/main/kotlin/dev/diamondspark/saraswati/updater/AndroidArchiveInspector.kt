/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 — the real ArchiveInspector :update-core's ApkVerifier
// needs (that module has no Android dependency, so it only defines the
// contract and a fake for its own tests). PackageManager.getPackageArchiveInfo
// parses the APK's manifest and — with GET_SIGNING_CERTIFICATES — its v2/v3
// signing block without actually installing anything, which is exactly
// what's needed to check package name, versionCode and signer before this
// file is ever handed to PackageInstaller.
//
// GET_SIGNING_CERTIFICATES was added in API 28; this module's minSdk is 26
// per the Global Constraints. On API 26/27 (no family phone runs anything
// that old — the Pixel 8 Pro is well past it), signingInfo comes back null
// and [inspect] returns null, which ApkVerifier already treats as "unable
// to read apk archive" — i.e. this fails CLOSED (refuses every update) on
// an OS old enough to lack the API, rather than silently skipping signer
// verification.
package dev.diamondspark.saraswati.updater

import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import dev.diamondspark.saraswati.updatecore.ArchiveInfo
import dev.diamondspark.saraswati.updatecore.ArchiveInspector
import java.io.File
import java.security.MessageDigest

class AndroidArchiveInspector(private val packageManager: PackageManager) : ArchiveInspector {

    override fun inspect(file: File): ArchiveInfo? {
        val packageInfo = packageManager.getPackageArchiveInfo(file.absolutePath, GET_SIGNING_CERTIFICATES)
            ?: return null
        val signingInfo = packageInfo.signingInfo ?: return null
        val packageName = packageInfo.packageName

        // apkContentsSigners is the set of certificates that actually signed
        // this APK's contents right now (current signer(s)) — never the
        // rotation lineage (signingCertificateHistory), which would also
        // include a long-retired key this app must no longer trust.
        val signers = signingInfo.apkContentsSigners
        if (signers.isNullOrEmpty()) return null
        val signerSha256 = signers.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        return ArchiveInfo(
            packageName = packageName,
            versionCode = packageInfo.longVersionCode,
            signerSha256 = signerSha256,
        )
    }
}

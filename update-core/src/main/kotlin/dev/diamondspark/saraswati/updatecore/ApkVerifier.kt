/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — the last check before a downloaded file is handed to
// the package installer. Order matters: the byte-for-byte hash is checked
// first, against the pinned value from the (already strictly validated)
// manifest, before this code does anything else with the file's contents —
// an archive that doesn't match the hash the manifest promised is never
// worth parsing further, successfully-parsed or not. Only once the hash
// matches do we trust [inspector] to open it and compare package name,
// versionCode and signer(s) against what the caller expects.
package dev.diamondspark.saraswati.updatecore

import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed interface VerifyResult {
    object Ok : VerifyResult
    data class Failed(val reason: String) : VerifyResult
}

object ApkVerifier {

    fun verify(
        file: File,
        asset: ApkAsset,
        expectedPackage: String,
        expectedVersionCode: Long,
        pinnedSignerSha256: Set<String>,
        inspector: ArchiveInspector,
    ): VerifyResult {
        val actualSha256 = sha256Of(file)
            ?: return VerifyResult.Failed("unable to read apk file")
        if (!actualSha256.equals(asset.sha256, ignoreCase = true)) {
            return VerifyResult.Failed("sha256 mismatch")
        }

        val info = inspector.inspect(file)
            ?: return VerifyResult.Failed("unable to read apk archive")

        if (info.packageName != expectedPackage) {
            return VerifyResult.Failed("package name mismatch")
        }
        if (info.versionCode != expectedVersionCode) {
            return VerifyResult.Failed("version code mismatch")
        }
        // Every signer the archive actually carries must be one we pinned,
        // and there must be at least one: an empty signer set means the
        // archive looks unsigned (or the inspector couldn't read the
        // signing block), which must never pass regardless of what's
        // pinned.
        if (info.signerSha256.isEmpty() || !pinnedSignerSha256.containsAll(info.signerSha256)) {
            return VerifyResult.Failed("signer not pinned")
        }

        return VerifyResult.Ok
    }

    private fun sha256Of(file: File): String? {
        if (!file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            null
        }
    }
}

private const val DEFAULT_BUFFER_SIZE = 8192

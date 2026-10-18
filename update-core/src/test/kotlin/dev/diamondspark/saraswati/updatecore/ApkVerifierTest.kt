/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — this is the last line of defense before a downloaded
// file is handed to the package installer, so every rejection branch
// (hash, package, version, signer, unreadable archive) is tested on its own,
// plus the one path where everything lines up.
package dev.diamondspark.saraswati.updatecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

private const val EXPECTED_PACKAGE = "dev.diamondspark.saraswati"
private const val EXPECTED_VERSION_CODE = 4200L
private val PINNED_SIGNER = setOf("f".repeat(64))

private fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun tempApkFile(contents: ByteArray, tempDir: File): File {
    val file = File.createTempFile("apk-verifier-test", ".apk", tempDir)
    file.writeBytes(contents)
    return file
}

private fun fakeInspector(info: ArchiveInfo?): ArchiveInspector = ArchiveInspector { info }

class ApkVerifierTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"))

    private fun assetFor(bytes: ByteArray): ApkAsset = ApkAsset(
        url = RELEASE_URL_PREFIX + "v1.0.0/saraswati.apk",
        sha256 = sha256Of(bytes),
        size = bytes.size.toLong(),
    )

    private fun matchingInfo(): ArchiveInfo = ArchiveInfo(
        packageName = EXPECTED_PACKAGE,
        versionCode = EXPECTED_VERSION_CODE,
        signerSha256 = PINNED_SIGNER,
    )

    @Test
    fun `sha256 mismatch fails`() {
        val bytes = "real apk bytes".toByteArray()
        val file = tempApkFile(bytes, tempDir)
        val asset = assetFor(bytes).copy(sha256 = "0".repeat(64))

        val result = ApkVerifier.verify(
            file = file,
            asset = asset,
            expectedPackage = EXPECTED_PACKAGE,
            expectedVersionCode = EXPECTED_VERSION_CODE,
            pinnedSignerSha256 = PINNED_SIGNER,
            inspector = fakeInspector(matchingInfo()),
        )

        assertTrue(result is VerifyResult.Failed)
    }

    @Test
    fun `wrong package fails`() {
        val bytes = "real apk bytes".toByteArray()
        val file = tempApkFile(bytes, tempDir)
        val asset = assetFor(bytes)
        val badInfo = matchingInfo().copy(packageName = "com.attacker.evil")

        val result = ApkVerifier.verify(
            file = file,
            asset = asset,
            expectedPackage = EXPECTED_PACKAGE,
            expectedVersionCode = EXPECTED_VERSION_CODE,
            pinnedSignerSha256 = PINNED_SIGNER,
            inspector = fakeInspector(badInfo),
        )

        assertTrue(result is VerifyResult.Failed)
    }

    @Test
    fun `unpinned signer fails`() {
        val bytes = "real apk bytes".toByteArray()
        val file = tempApkFile(bytes, tempDir)
        val asset = assetFor(bytes)
        val badInfo = matchingInfo().copy(signerSha256 = setOf("a".repeat(64)))

        val result = ApkVerifier.verify(
            file = file,
            asset = asset,
            expectedPackage = EXPECTED_PACKAGE,
            expectedVersionCode = EXPECTED_VERSION_CODE,
            pinnedSignerSha256 = PINNED_SIGNER,
            inspector = fakeInspector(badInfo),
        )

        assertTrue(result is VerifyResult.Failed)
    }

    @Test
    fun `empty signer set fails even with an empty pinned set`() {
        // An "anything goes" signer policy is never correct here: an empty
        // actual signer set means the archive looks unsigned/unparseable,
        // which must never pass regardless of what's pinned.
        val bytes = "real apk bytes".toByteArray()
        val file = tempApkFile(bytes, tempDir)
        val asset = assetFor(bytes)
        val badInfo = matchingInfo().copy(signerSha256 = emptySet())

        val result = ApkVerifier.verify(
            file = file,
            asset = asset,
            expectedPackage = EXPECTED_PACKAGE,
            expectedVersionCode = EXPECTED_VERSION_CODE,
            pinnedSignerSha256 = emptySet(),
            inspector = fakeInspector(badInfo),
        )

        assertTrue(result is VerifyResult.Failed)
    }

    @Test
    fun `version code mismatch fails`() {
        val bytes = "real apk bytes".toByteArray()
        val file = tempApkFile(bytes, tempDir)
        val asset = assetFor(bytes)
        val badInfo = matchingInfo().copy(versionCode = EXPECTED_VERSION_CODE - 1)

        val result = ApkVerifier.verify(
            file = file,
            asset = asset,
            expectedPackage = EXPECTED_PACKAGE,
            expectedVersionCode = EXPECTED_VERSION_CODE,
            pinnedSignerSha256 = PINNED_SIGNER,
            inspector = fakeInspector(badInfo),
        )

        assertTrue(result is VerifyResult.Failed)
    }

    @Test
    fun `unreadable archive fails`() {
        val bytes = "real apk bytes".toByteArray()
        val file = tempApkFile(bytes, tempDir)
        val asset = assetFor(bytes)

        val result = ApkVerifier.verify(
            file = file,
            asset = asset,
            expectedPackage = EXPECTED_PACKAGE,
            expectedVersionCode = EXPECTED_VERSION_CODE,
            pinnedSignerSha256 = PINNED_SIGNER,
            inspector = fakeInspector(null),
        )

        assertTrue(result is VerifyResult.Failed)
    }

    @Test
    fun `missing file fails without throwing`() {
        val missing = File(tempDir, "does-not-exist-${System.nanoTime()}.apk")
        val asset = assetFor("whatever".toByteArray())

        val result = ApkVerifier.verify(
            file = missing,
            asset = asset,
            expectedPackage = EXPECTED_PACKAGE,
            expectedVersionCode = EXPECTED_VERSION_CODE,
            pinnedSignerSha256 = PINNED_SIGNER,
            inspector = fakeInspector(matchingInfo()),
        )

        assertTrue(result is VerifyResult.Failed)
    }

    @Test
    fun `all matching passes`() {
        val bytes = "real apk bytes".toByteArray()
        val file = tempApkFile(bytes, tempDir)
        val asset = assetFor(bytes)

        val result = ApkVerifier.verify(
            file = file,
            asset = asset,
            expectedPackage = EXPECTED_PACKAGE,
            expectedVersionCode = EXPECTED_VERSION_CODE,
            pinnedSignerSha256 = PINNED_SIGNER,
            inspector = fakeInspector(matchingInfo()),
        )

        assertEquals(VerifyResult.Ok, result)
    }
}

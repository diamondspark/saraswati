/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 4 — strict-parsing tests for the update manifest. A
// manifest a weak parser accepts by accident is a step toward installing
// whatever it points at, so every rejection path below is as load-bearing as
// the happy path.
package dev.diamondspark.saraswati.updatecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun goldenManifestJson(): String {
    val repoRoot = System.getProperty("saraswati.repoRoot")
        ?: error("saraswati.repoRoot system property not set by the Gradle test task")
    return File(repoRoot, "security/fixtures/manifest_stable.json").readText()
}

private fun ApkAsset.toJson(): String = """
    {
      "url": "$url",
      "sha256": "$sha256",
      "size": $size
    }
""".trimIndent()

private val VALID_APK = ApkAsset(
    url = RELEASE_URL_PREFIX + "v1.0.0/saraswati-v1.0.0.apk",
    sha256 = "a".repeat(64),
    size = 1_000_000L,
)

private fun manifestJson(
    schema: Int = 1,
    channel: String = "stable",
    versionCode: Long = 100,
    versionName: String = "1.0.0",
    apk: ApkAsset = VALID_APK,
    urgent: Boolean = false,
    notes: String = "Routine update.",
    publishedAt: String = "2026-10-17T00:00:00Z",
    extraTopLevelField: String = "",
): String = """
    {
      "schema": $schema,
      "channel": "$channel",
      "versionCode": $versionCode,
      "versionName": "$versionName",
      "apk": ${apk.toJson()},
      "urgent": $urgent,
      "notes": "$notes",
      "publishedAt": "$publishedAt"
      $extraTopLevelField
    }
""".trimIndent()

class ManifestParserTest {

    @Test
    fun `parses the golden manifest`() {
        val result = ManifestParser.parse(goldenManifestJson())
        assertTrue(result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals(1, manifest.schema)
        assertEquals("stable", manifest.channel)
        assertEquals(4200L, manifest.versionCode)
        assertEquals("1.4.2", manifest.versionName)
        assertEquals(
            RELEASE_URL_PREFIX + "v1.4.2/saraswati-v1.4.2-arm64.apk",
            manifest.apk.url,
        )
        assertEquals(64, manifest.apk.sha256.length)
        assertEquals(52_428_800L, manifest.apk.size)
        assertEquals(false, manifest.urgent)
        assertEquals("2026-10-17T00:00:00Z", manifest.publishedAt)
        assertEquals(7L, manifest.updater?.versionCode)
        assertEquals(10_485_760L, manifest.updater?.apk?.size)
    }

    @Test
    fun `rejects schema 2`() {
        val result = ManifestParser.parse(manifestJson(schema = 2))
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects http url`() {
        val httpApk = VALID_APK.copy(url = "http://github.com/diamondspark/saraswati/releases/download/v1.0.0/x.apk")
        val result = ManifestParser.parse(manifestJson(apk = httpApk))
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects url outside the release prefix`() {
        val otherRepoApk = VALID_APK.copy(
            url = "https://github.com/someone-else/other-repo/releases/download/v1.0.0/x.apk",
        )
        val otherHostApk = VALID_APK.copy(url = "https://evil.example/saraswati-v1.0.0.apk")
        assertTrue(ManifestParser.parse(manifestJson(apk = otherRepoApk)).isFailure)
        assertTrue(ManifestParser.parse(manifestJson(apk = otherHostApk)).isFailure)
    }

    @Test
    fun `rejects a sha256 that is not 64 lowercase hex`() {
        val tooShort = VALID_APK.copy(sha256 = "a".repeat(63))
        val tooLong = VALID_APK.copy(sha256 = "a".repeat(65))
        val uppercase = VALID_APK.copy(sha256 = "A".repeat(64))
        val nonHex = VALID_APK.copy(sha256 = "g".repeat(64))
        assertTrue(ManifestParser.parse(manifestJson(apk = tooShort)).isFailure)
        assertTrue(ManifestParser.parse(manifestJson(apk = tooLong)).isFailure)
        assertTrue(ManifestParser.parse(manifestJson(apk = uppercase)).isFailure)
        assertTrue(ManifestParser.parse(manifestJson(apk = nonHex)).isFailure)
    }

    @Test
    fun `rejects size above 100 MiB`() {
        val tooBig = VALID_APK.copy(size = DownloadPolicy.MAX_APK_BYTES + 1)
        assertTrue(ManifestParser.parse(manifestJson(apk = tooBig)).isFailure)
    }

    @Test
    fun `rejects a non-positive size`() {
        val zero = VALID_APK.copy(size = 0L)
        val negative = VALID_APK.copy(size = -1L)
        assertTrue(ManifestParser.parse(manifestJson(apk = zero)).isFailure)
        assertTrue(ManifestParser.parse(manifestJson(apk = negative)).isFailure)
    }

    @Test
    fun `rejects unknown channel`() {
        val result = ManifestParser.parse(manifestJson(channel = "nightly"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects notes above 1000 chars`() {
        val longNotes = "x".repeat(1001)
        val result = ManifestParser.parse(manifestJson(notes = longNotes))
        assertTrue(result.isFailure)
    }

    @Test
    fun `accepts notes at exactly 1000 chars`() {
        val maxNotes = "x".repeat(1000)
        val result = ManifestParser.parse(manifestJson(notes = maxNotes))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `rejects a non-positive versionCode`() {
        assertTrue(ManifestParser.parse(manifestJson(versionCode = 0)).isFailure)
        assertTrue(ManifestParser.parse(manifestJson(versionCode = -5)).isFailure)
    }

    @Test
    fun `rejects malformed json`() {
        val result = ManifestParser.parse("{ not valid json")
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects json missing a required field`() {
        val result = ManifestParser.parse(
            """
            {
              "schema": 1,
              "channel": "stable",
              "versionCode": 1,
              "versionName": "1.0.0"
            }
            """.trimIndent(),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects an updater asset outside the release prefix`() {
        val badUpdater = """
            ,"updater": {
              "versionCode": 1,
              "apk": {
                "url": "https://evil.example/updater.apk",
                "sha256": "${"b".repeat(64)}",
                "size": 1000
              }
            }
        """.trimIndent()
        val result = ManifestParser.parse(manifestJson(extraTopLevelField = badUpdater))
        assertTrue(result.isFailure)
    }

    @Test
    fun `ignores unknown extra fields`() {
        val result = ManifestParser.parse(manifestJson(extraTopLevelField = """, "futureField": "whatever", "nested": {"a": 1}"""))
        assertTrue(result.isSuccess)
    }
}

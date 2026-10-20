/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix pass 2 (P5-I4) — every HTTP client built in this app must carry the local-song egress guard. A local
// song's id (`local:<n>`) and title name a file on this phone, so no client may send them to a host. This scans the
// sources that construct a client and fails on any that does not install LocalIdEgressGuard (Ktor) or
// LocalIdEgressInterceptor (OkHttp), so a new client cannot slip past the guard unnoticed.
package com.metrolist.music.saraswati.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HttpClientEgressScanTest {
    // A construction of a Ktor client (`HttpClient(OkHttp) {`, `HttpClient {`, `HttpClient()`) or of an OkHttp client
    // (`OkHttpClient.Builder()`, `OkHttpClient()`), in any line layout.
    private val construction = Regex("""\b(HttpClient\s*[({]|OkHttpClient\s*\.\s*Builder\s*\(|OkHttpClient\s*\()""")

    private val guards = listOf("LocalIdEgressGuard)", "LocalIdEgressInterceptor)")

    // Clients that cannot carry song data, by file name suffix, with the reason. The updater is a separate APK with no
    // innertube dependency: it fetches fixed release-manifest and release-asset URLs only.
    private val exempt =
        mapOf(
            "dev/diamondspark/saraswati/updater/HttpFetcher.kt" to
                "updater APK: fixed release-manifest and release-asset URLs, no song data, no innertube dependency",
        )

    // Gradle runs unit tests from the app module directory; a run from the repo root also works.
    private val repo: File = if (File("src/main/kotlin").isDirectory) File("..") else File(".")

    private fun scannedFiles(): List<File> =
        listOf("app", "innertube", "updater", "update-core")
            .map { File(repo, "$it/src/main/kotlin") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun exemptReason(file: File): String? =
        exempt.entries.firstOrNull { file.invariantSeparatorsPath.endsWith(it.key) }?.value

    @Test
    fun `every HTTP client construction installs the local-song egress guard`() {
        val constructing = scannedFiles().filter { construction.containsMatchIn(it.readText()) }
        assertTrue("the scan found only ${constructing.size} client sites: it is looking in the wrong place", constructing.size >= 10)
        val offenders =
            constructing
                .filter { exemptReason(it) == null }
                .filter { file -> guards.none { file.readText().contains(it) } }
                .map { it.name }
        assertTrue("HTTP clients without the local-song egress guard: $offenders", offenders.isEmpty())
    }

    @Test
    fun `every exemption names a file that exists and still builds a client`() {
        for (suffix in exempt.keys) {
            val file = scannedFiles().firstOrNull { it.invariantSeparatorsPath.endsWith(suffix) }
            assertTrue("exempt file gone: $suffix", file != null)
            assertTrue("exempt file no longer builds a client: $suffix", construction.containsMatchIn(file!!.readText()))
        }
    }
}

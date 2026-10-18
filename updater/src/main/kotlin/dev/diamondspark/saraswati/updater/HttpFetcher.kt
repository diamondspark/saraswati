/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 — the ONE place in this app that talks to the network.
// Built on OkHttp (already the most-audited HTTP client in this repo:
// :app pulls it transitively through media3, coil and ktor-client-okhttp),
// which matters here specifically because DownloadPolicy's own review
// (Task 4) was verified against OkHttp's real redirect/URL behavior — most
// importantly, OkHttp does not re-run percent-decoding on a path segment it
// has already resolved, so a double-encoded dot segment (`%252e%252e`)
// reaches it as one opaque, non-special path segment, never as a second
// decode pass that would turn it into a real `..`. That residual gap
// (tracked from Task 4's review, Ruling Q14) is therefore not exploitable
// through this client; a different HTTP client would need that same
// property re-verified before being trusted the same way.
//
// Automatic redirects are off (OkHttpClient.followRedirects = false):
// every redirect is followed by hand, one at a time, re-validating the
// target host through DownloadPolicy.isAllowedRedirect before the next
// request is ever made — the whole point of Review Focus item 1
// (downgrade/redirect attacks on the update path).
package dev.diamondspark.saraswati.updater

import dev.diamondspark.saraswati.updatecore.DownloadPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {

    /** Blocking. Throws [IOException] on any network/HTTP failure. Caller supplies the background thread. */
    fun fetchText(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("unexpected response ${response.code} fetching $url")
            }
            return response.body.string()
        }
    }

    /**
     * Blocking download of [startUrl] into [destination], following at most
     * [DownloadPolicy.MAX_REDIRECTS] redirects by hand, each one re-checked
     * against [DownloadPolicy.isAllowedRedirect]. Throws [IOException] (a
     * network/HTTP failure) or [SecurityException] (a disallowed start URL,
     * redirect, or a body bigger than [DownloadPolicy.MAX_APK_BYTES]) —
     * callers distinguish the two only to log them; both end the flow in a
     * non-retryable Error via VerifyResult.Failed, same as a verification
     * failure (see UpdateFlow's file header).
     */
    fun download(startUrl: String, destination: File, onProgress: (bytesSoFar: Long) -> Unit) {
        if (!DownloadPolicy.isAllowedStartUrl(startUrl)) {
            throw SecurityException("disallowed start url: $startUrl")
        }

        var currentUrl = startUrl
        var redirects = 0
        while (true) {
            val request = Request.Builder().url(currentUrl).get().build()
            val response = client.newCall(request).execute()
            if (response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) {
                    throw IOException("redirect from $currentUrl with no Location header")
                }
                val nextUrl = response.request.url.resolve(location)?.toString()
                    ?: throw IOException("unresolvable redirect location: $location")
                if (!DownloadPolicy.isAllowedRedirect(currentUrl, nextUrl)) {
                    throw SecurityException("disallowed redirect from $currentUrl to $nextUrl")
                }
                redirects++
                if (redirects > DownloadPolicy.MAX_REDIRECTS) {
                    throw IOException("too many redirects starting from $startUrl")
                }
                currentUrl = nextUrl
                continue
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("unexpected response ${resp.code} downloading $currentUrl")
                }
                val body = resp.body
                destination.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > DownloadPolicy.MAX_APK_BYTES) {
                                throw SecurityException("download exceeded ${DownloadPolicy.MAX_APK_BYTES} bytes")
                            }
                            out.write(buffer, 0, read)
                            onProgress(total)
                        }
                    }
                }
            }
            return
        }
    }
}

private const val DOWNLOAD_BUFFER_SIZE = 8192

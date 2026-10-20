package com.metrolist.music.saraswati.offline

import android.net.Uri
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class OfflinePlayabilityTest {
    private var granted = true
    private var downloads = emptyMap<String, Download>()
    private var cachedBytes = emptyMap<String, Long>()
    private var lengths = emptyMap<String, Long>()

    private val cache: Cache =
        Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            check(method.name == "isCached") { "unexpected ${method.name}" }
            val have = cachedBytes[args[0] as String] ?: 0L
            (args[1] as Long) + (args[2] as Long) <= have
        } as Cache

    private val playability =
        DefaultOfflinePlayability(
            downloads = { downloads },
            playerCache = cache,
            contentLength = { lengths[it] },
            isLocal = { it.startsWith("local:") },
            localGranted = { granted },
        )

    private fun playable(id: String) = runBlocking { playability.isPlayableOffline(id) }

    private fun download(
        id: String,
        state: Int,
    ) = Download(DownloadRequest.Builder(id, Uri.parse("https://example.invalid/$id")).build(), state, 0L, 0L, 100L, 0, Download.FAILURE_REASON_NONE)

    @Test
    fun `a local song is playable only with the permission`() {
        assertTrue(playable("local:42"))
        granted = false
        assertFalse(playable("local:42"))
    }

    @Test
    fun `a completed download is playable`() {
        downloads = mapOf("done" to download("done", Download.STATE_COMPLETED), "busy" to download("busy", Download.STATE_DOWNLOADING))

        assertTrue(playable("done"))
        assertFalse(playable("busy"))
    }

    @Test
    fun `a fully cached song is playable`() {
        lengths = mapOf("song" to 1_000L)
        cachedBytes = mapOf("song" to 1_000L)

        assertTrue(playable("song"))
    }

    @Test
    fun `a partly cached song is not`() {
        lengths = mapOf("song" to 1_000L)
        cachedBytes = mapOf("song" to 600L)

        assertFalse(playable("song"))
    }

    @Test
    fun `unknown length is not`() {
        cachedBytes = mapOf("song" to 1_000L)

        assertFalse(playable("song"))
    }
}

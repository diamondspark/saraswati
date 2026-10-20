/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 17 — the Cast relay controller: when the relay serves, what the queue looks like, and
// when every caller falls back to upstream's stream path.
package com.metrolist.music.saraswati.castrelay

import androidx.media3.common.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastRelayControllerTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val servers = mutableListOf<RelayHttpServer>()
    private val relayUrl = Regex("""http://127\.0\.0\.1:\d+/[0-9a-f]{64}/[0-9a-f]{32}""")

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private fun controller(
        featureOn: Boolean = true,
        buildOn: Boolean = true,
        address: Inet4Address? = loopback,
        stored: Map<String, String> = mapOf("v0" to "audio/webm"),
        sourceMime: String = "audio/ogg",
    ): CastRelayController =
        CastRelayController(
            random = SecureRandom(),
            source = FakeSource(sourceMime),
            storedMimeType = { stored[it] },
            featureEnabled = { featureOn },
            localAddress = { address },
            buildEnabled = buildOn,
            newServer = { a, s, src -> RelayHttpServer(a, s, src).also { servers += it } },
        )

    private fun queue(count: Int): List<MediaItem> =
        (0 until count).map { MediaItem.Builder().setMediaId("v$it").setUri("https://music.invalid/$it").build() }

    @Test
    fun `uses relay URLs for the whole queue when enabled`() =
        runBlocking {
            val controller = controller()
            assertTrue(controller.start())
            val items = controller.mediaItems(queue(3))!!
            assertEquals(3, items.size)
            assertEquals(listOf("v0", "v1", "v2"), items.map { it.mediaId })
            items.forEach { assertTrue(relayUrl.matches(it.localConfiguration!!.uri.toString())) }
            controller.stop()
        }

    @Test
    fun `caps the queue at 200`() =
        runBlocking {
            val controller = controller(stored = (0 until 250).associate { "v$it" to "audio/webm" })
            assertTrue(controller.start())
            assertEquals(CastRelayController.CAST_QUEUE_MAX, controller.mediaItems(queue(250))!!.size)
            controller.stop()
        }

    @Test
    fun `mime type comes from the stored format, not a hardcoded mp4`() =
        runBlocking {
            val controller = controller(stored = mapOf("v0" to "audio/webm"))
            controller.start()
            val item = controller.mediaItems(queue(1))!!.single()
            assertEquals("audio/webm", item.localConfiguration!!.mimeType)
            controller.stop()
        }

    @Test
    fun `an unknown mime type is resolved once through the stream path`() =
        runBlocking {
            val controller = controller(stored = emptyMap(), sourceMime = "audio/ogg")
            controller.start()
            val item = controller.mediaItems(queue(1))!!.single()
            assertEquals("audio/ogg", item.localConfiguration!!.mimeType)
            controller.stop()
        }

    @Test
    fun `falls back when cast_relay is off`() =
        runBlocking {
            val controller = controller(featureOn = false)
            assertFalse(controller.start())
            assertNull(controller.mediaItems(queue(3)))
        }

    @Test
    fun `falls back when the build flag is off`() =
        runBlocking {
            val controller = controller(buildOn = false)
            assertFalse(controller.start())
            assertNull(controller.mediaItems(queue(3)))
        }

    @Test
    fun `falls back with no Wi-Fi address`() =
        runBlocking {
            val controller = controller(address = null)
            assertFalse(controller.start())
            assertNull(controller.mediaItems(queue(3)))
        }

    @Test
    fun `stops the server when the cast session ends`() =
        runBlocking {
            val controller = controller()
            controller.start()
            val port = controller.mediaItems(queue(1))!!.single().localConfiguration!!.uri.port
            controller.stop()
            assertFalse(controller.isActive)
            assertNull(controller.mediaItems(queue(1)))
            assertClosed(port)
        }

    @Test
    fun `stops the server when Wi-Fi is lost`() =
        runBlocking {
            val controller = controller()
            controller.start()
            val port = controller.mediaItems(queue(1))!!.single().localConfiguration!!.uri.port
            controller.onWifiLost()
            assertFalse(controller.isActive)
            assertNull(controller.mediaItems(queue(1)))
            assertClosed(port)
        }

    private fun assertClosed(port: Int) {
        try {
            Socket(loopback, port).close()
            fail("relay port should be closed")
        } catch (expected: ConnectException) {
        }
    }

    private class FakeSource(
        private val mime: String,
    ) : RelayAudioSource {
        override suspend fun open(
            videoId: String,
            range: LongRange?,
        ): RelayStream {
            val data = ByteArray(10) { it.toByte() }
            val start = (range?.first ?: 0L).coerceAtMost(data.size.toLong()).toInt()
            return RelayStream(mime, data.size.toLong(), ByteArrayInputStream(data, start, data.size - start))
        }
    }
}

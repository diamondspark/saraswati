/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 17 — the Cast relay controller: when the relay serves, what the queue looks like, and
// when every caller falls back to upstream's stream path.
package com.metrolist.music.saraswati.castrelay

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import com.metrolist.music.saraswati.localmedia.LocalFile
import com.metrolist.music.saraswati.localmedia.LocalFileOpener
import com.metrolist.music.saraswati.localmedia.LocalSongIds
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
        source: RelayAudioSource = FakeSource(sourceMime),
        storedThrows: Boolean = false,
    ): CastRelayController =
        CastRelayController(
            random = SecureRandom(),
            source = source,
            storedMimeType = { if (storedThrows) throw PlaybackException("db", null, PlaybackException.ERROR_CODE_UNSPECIFIED) else stored[it] },
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

    @Test
    fun `a source that throws PlaybackException falls back instead of crashing`() =
        runBlocking {
            val controller = controller(stored = emptyMap(), source = ThrowingSource())
            assertTrue(controller.start())
            assertNull(controller.mediaItems(queue(2)))
            controller.stop()
        }

    @Test
    fun `a storedMimeType that throws falls back instead of crashing`() =
        runBlocking {
            val controller = controller(stored = emptyMap(), storedThrows = true, source = ThrowingSource())
            assertTrue(controller.start())
            assertNull(controller.mediaItems(queue(2)))
            controller.stop()
        }

    @Test
    fun `a server that fails to build leaves the relay off`() {
        val controller =
            CastRelayController(
                random = SecureRandom(),
                source = FakeSource("audio/ogg"),
                storedMimeType = { null },
                featureEnabled = { true },
                localAddress = { loopback },
                buildEnabled = true,
                newServer = { _, _, _ -> throw IllegalStateException("no socket") },
            )
        assertFalse(controller.start())
        assertFalse(controller.isActive)
    }

    @Test
    fun `live resolves are capped to the current song and the next ten`() =
        runBlocking {
            val source = CountingSource()
            val controller = controller(stored = emptyMap(), source = source)
            controller.start()
            val result = controller.relayQueue(queue(60), centerIndex = 0)!!
            assertEquals(1 + CastRelayController.RESOLVE_AHEAD, source.opened.size)
            assertEquals(1 + CastRelayController.RESOLVE_AHEAD, result.items.size)
            assertEquals(0, result.startIndex)
            controller.stop()
        }

    @Test
    fun `songs with a stored format past the window still ride along until the first unknown one`() =
        runBlocking {
            val stored = (0 until 40).filter { it != 30 }.associate { "v$it" to "audio/webm" }
            val source = CountingSource()
            val controller = controller(stored = stored, source = source)
            controller.start()
            val result = controller.relayQueue(queue(60), centerIndex = 0)!!
            assertEquals(30, result.items.size)
            assertTrue(source.opened.isEmpty())
            controller.stop()
        }

    @Test
    fun `an unresolved song before the current one is dropped and the start index shifts`() =
        runBlocking {
            val stored = (0 until 10).filter { it != 2 }.associate { "v$it" to "audio/webm" }
            val controller = controller(stored = stored, source = CountingSource())
            controller.start()
            val result = controller.relayQueue(queue(10), centerIndex = 5)!!
            assertEquals(listOf("v3", "v4", "v5", "v6", "v7", "v8", "v9"), result.items.map { it.mediaId })
            assertEquals(2, result.startIndex)
            controller.stop()
        }

    @Test
    fun `an unresolvable current song falls back to upstream`() =
        runBlocking {
            val controller = controller(stored = mapOf("v1" to "audio/webm"), source = ThrowingSource())
            controller.start()
            assertNull(controller.relayQueue(queue(3), centerIndex = 0))
            controller.stop()
        }

    @Test
    fun `mediaItems uses the local mime type`() =
        runBlocking {
            val localOpener = LocalFileOpener { LocalFile("audio/mp4", 1L, ByteArrayInputStream(ByteArray(1))) }
            val controller =
                CastRelayController(
                    random = SecureRandom(),
                    source = RoutingRelayAudioSource(LocalRelayAudioSource(localOpener), FakeSource("audio/ogg")),
                    storedMimeType = { if (LocalSongIds.isLocal(it)) "audio/mp4" else null },
                    featureEnabled = { true },
                    localAddress = { loopback },
                    buildEnabled = true,
                    newServer = { a, s, src -> RelayHttpServer(a, s, src).also { servers += it } },
                )
            controller.start()
            val item = controller.mediaItems(listOf(MediaItem.Builder().setMediaId("local:4815162342").build()))!!.single()
            assertEquals("audio/mp4", item.localConfiguration!!.mimeType)
            controller.stop()
        }

    @Test
    fun `with the Local files switch off no local song is put in the queue`() =
        runBlocking {
            val localOpener = LocalFileOpener { LocalFile("audio/mp4", 1L, ByteArrayInputStream(ByteArray(1))) }
            val controller =
                CastRelayController(
                    random = SecureRandom(),
                    source =
                        RoutingRelayAudioSource(
                            LocalRelayAudioSource(localOpener, allowed = { false }),
                            FakeSource("audio/ogg"),
                        ),
                    // Production's local MIME lookup is gated by the same switch, so it knows nothing either.
                    storedMimeType = { null },
                    featureEnabled = { true },
                    localAddress = { loopback },
                    buildEnabled = true,
                    newServer = { a, s, src -> RelayHttpServer(a, s, src).also { servers += it } },
                )
            controller.start()
            assertNull(controller.mediaItems(listOf(MediaItem.Builder().setMediaId("local:4815162342").build())))
            controller.stop()
        }

    @Test
    fun `a relay URL for a local song contains neither its id nor a path`() =
        runBlocking {
            val localOpener = LocalFileOpener { LocalFile("audio/mp4", 1L, ByteArrayInputStream(ByteArray(1))) }
            val controller =
                CastRelayController(
                    random = SecureRandom(),
                    source = RoutingRelayAudioSource(LocalRelayAudioSource(localOpener), FakeSource("audio/ogg")),
                    storedMimeType = { if (LocalSongIds.isLocal(it)) "audio/mp4" else null },
                    featureEnabled = { true },
                    localAddress = { loopback },
                    buildEnabled = true,
                    newServer = { a, s, src -> RelayHttpServer(a, s, src).also { servers += it } },
                )
            controller.start()
            val uri = controller.mediaItems(listOf(MediaItem.Builder().setMediaId("local:4815162342").build()))!!.single().localConfiguration!!.uri.toString()
            assertTrue(relayUrl.matches(uri))
            assertFalse(uri.contains("4815162342"))
            assertFalse(uri.contains("local"))
            assertFalse(uri.contains("content"))
            controller.stop()
        }

    @Test
    fun `without the relay, local items are left out of the Cast window and the rest still cast`() {
        val items =
            listOf("v0", "local:12", "v2").map { MediaItem.Builder().setMediaId(it).setUri("https://music.invalid/$it").build() }
        assertEquals(listOf("v0", "v2"), castableWithoutRelay(items).map { it.mediaId })
    }

    private fun assertClosed(port: Int) {
        try {
            Socket(loopback, port).close()
            fail("relay port should be closed")
        } catch (expected: ConnectException) {
        }
    }

    private class ThrowingSource : RelayAudioSource {
        override suspend fun open(
            videoId: String,
            range: LongRange?,
        ): RelayStream? = throw PlaybackException("unavailable", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    }

    private class CountingSource : RelayAudioSource {
        val opened = mutableListOf<String>()

        override suspend fun open(
            videoId: String,
            range: LongRange?,
        ): RelayStream {
            opened += videoId
            return RelayStream("audio/ogg", 1L, ByteArrayInputStream(ByteArray(1)))
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

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 17 fix wave — the relay's cache reader never writes into the downloads cache, and an
// unresolvable stream (PlaybackException, not IOException) becomes "cannot serve" rather than a crash.
package com.metrolist.music.saraswati.castrelay

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheRelayAudioSourceTest {
    @get:Rule val folder = TemporaryFolder()

    private var cache: SimpleCache? = null

    @After
    fun tearDown() {
        cache?.release()
    }

    private fun newCache(): SimpleCache =
        SimpleCache(folder.newFolder("cache"), NoOpCacheEvictor()).also { cache = it }

    @Test
    fun `streaming a song through the relay leaves the cache empty`() =
        runBlocking {
            val audio = ByteArray(5_000) { it.toByte() }
            val cache = newCache()
            val source = CacheRelayAudioSource(cache, { FakeUpstream(audio) }, { "audio/webm" })

            val stream = source.open("vid", null)
            assertNotNull(stream)
            assertArrayEquals(audio, stream!!.input.readBytes())
            stream.input.close()

            assertTrue("the relay wrote into the cache: ${cache.keys}", cache.keys.isEmpty())
            assertEquals(0L, cache.cacheSpace)
        }

    @Test
    fun `an upstream that throws PlaybackException gives null, not an exception`() =
        runBlocking {
            val source =
                CacheRelayAudioSource(newCache(), { FakeUpstream(ByteArray(0), failOpen = true) }, { "audio/webm" })
            assertNull(source.open("vid", null))
        }

    @Test
    fun `a stored-format lookup that throws gives null`() =
        runBlocking {
            val source =
                CacheRelayAudioSource(
                    newCache(),
                    { FakeUpstream(ByteArray(10)) },
                    { throw IllegalStateException("database closed") },
                )
            assertNull(source.open("vid", null))
        }

    private class FakeUpstream(
        private val data: ByteArray,
        private val failOpen: Boolean = false,
    ) : DataSource {
        private var position = 0
        private var uri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            if (failOpen) {
                throw PlaybackException("no internet", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            return (data.size - position).toLong()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= data.size) return -1
            val n = minOf(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, n)
            position += n
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() = Unit
    }
}

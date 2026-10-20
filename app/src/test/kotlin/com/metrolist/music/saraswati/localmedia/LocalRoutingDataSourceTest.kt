package com.metrolist.music.saraswati.localmedia

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalRoutingDataSourceTest {
    private class Recording : DataSource.Factory {
        var created = 0
        val opened = mutableListOf<DataSpec>()

        override fun createDataSource(): DataSource {
            created++
            return object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) {}

                override fun open(dataSpec: DataSpec): Long {
                    opened += dataSpec
                    return -1
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int) = -1

                override fun getUri(): Uri? = opened.lastOrNull()?.uri

                override fun close() {}
            }
        }
    }

    @Test
    fun `a local key opens the content URI and never touches the remote factory`() {
        val local = Recording()
        val remote = Recording()
        val source = LocalRoutingDataSource.Factory(local, remote).createDataSource()
        source.open(DataSpec.Builder().setUri("local:42").setKey("local:42").build())
        assertEquals(0, remote.created)
        assertEquals(1, local.created)
        assertEquals("content://media/external/audio/media/42", local.opened.single().uri.toString())
        assertEquals("local:42", local.opened.single().key)
    }

    @Test
    fun `a video id goes to the remote factory unchanged`() {
        val local = Recording()
        val remote = Recording()
        val source = LocalRoutingDataSource.Factory(local, remote).createDataSource()
        val spec = DataSpec.Builder().setUri("dQw4w9WgXcQ").setKey("dQw4w9WgXcQ").build()
        source.open(spec)
        assertEquals(0, local.created)
        assertEquals(spec, remote.opened.single())
    }

    @Test
    fun `a deleted local file fails open with FileNotFoundException`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source =
            LocalRoutingDataSource.Factory(DefaultDataSource.Factory(context), Recording()).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri("local:999").setKey("local:999").build())
            fail("expected the open to fail")
        } catch (e: IOException) {
            assertNotNull(generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is FileNotFoundException })
        }
    }

    @Test
    fun `a malformed local key is a missing file too`() {
        val source = LocalRoutingDataSource.Factory(Recording(), Recording()).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri("local:abc").setKey("local:abc").build())
            fail("expected the open to fail")
        } catch (e: IOException) {
            assertTrue(e is FileNotFoundException || e.cause is FileNotFoundException)
        }
    }
}

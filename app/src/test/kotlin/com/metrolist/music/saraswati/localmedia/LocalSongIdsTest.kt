package com.metrolist.music.saraswati.localmedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalSongIdsTest {
    @Test
    fun `of and mediaStoreId round-trip`() {
        assertEquals("local:42", LocalSongIds.of(42))
        assertEquals(42L, LocalSongIds.mediaStoreId(LocalSongIds.of(42)))
        assertEquals(Long.MAX_VALUE, LocalSongIds.mediaStoreId(LocalSongIds.of(Long.MAX_VALUE)))
        assertTrue(LocalSongIds.isLocal(LocalSongIds.of(7)))
    }

    @Test
    fun `isLocal is false for null, a video id and "local colon"`() {
        assertFalse(LocalSongIds.isLocal(null))
        assertFalse(LocalSongIds.isLocal("dQw4w9WgXcQ"))
        assertFalse(LocalSongIds.isLocal("local:"))
        assertFalse(LocalSongIds.isLocal("xlocal:42"))
        assertNull(LocalSongIds.mediaStoreId("local:"))
        assertNull(LocalSongIds.mediaStoreId("local:4x"))
    }

    @Test
    fun `contentUri is null for a video id`() {
        assertNull(LocalSongIds.contentUri("dQw4w9WgXcQ"))
        val uri = LocalSongIds.contentUri("local:42")
        assertNotNull(uri)
        assertEquals("content://media/external/audio/media/42", uri.toString())
    }
}

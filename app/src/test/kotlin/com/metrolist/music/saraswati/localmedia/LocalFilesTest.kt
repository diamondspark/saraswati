package com.metrolist.music.saraswati.localmedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalFilesTest {
    @Test
    fun `on by default only once access is granted`() {
        assertTrue(LocalFiles.enabled(null, LocalAccess.GRANTED))
        assertFalse(LocalFiles.enabled(null, LocalAccess.NOT_GRANTED))
        assertFalse(LocalFiles.enabled(null, LocalAccess.UNSUPPORTED))
    }

    @Test
    fun `off wins even when access is granted`() {
        assertFalse(LocalFiles.enabled(false, LocalAccess.GRANTED))
        assertTrue(LocalFiles.enabled(true, LocalAccess.GRANTED))
        assertFalse(LocalFiles.enabled(true, LocalAccess.NOT_GRANTED))
    }

    @Test
    fun `switched off looks like an unsupported phone to the car and the library entry`() {
        assertEquals(LocalAccess.UNSUPPORTED, LocalFiles.visibleAccess(LocalAccess.GRANTED, false))
        assertEquals(LocalAccess.UNSUPPORTED, LocalFiles.visibleAccess(LocalAccess.NOT_GRANTED, false))
        assertEquals(LocalAccess.GRANTED, LocalFiles.visibleAccess(LocalAccess.GRANTED, null))
        assertEquals(LocalAccess.NOT_GRANTED, LocalFiles.visibleAccess(LocalAccess.NOT_GRANTED, true))
    }

    @Test
    fun `access is unsupported below Android 13 whatever the permission says`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(LocalAccess.UNSUPPORTED, localAccess(context, sdkInt = 32))
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        assertEquals(LocalAccess.UNSUPPORTED, localAccess(context, sdkInt = 32))
        assertEquals(LocalAccess.GRANTED, localAccess(context, sdkInt = 33))
    }

    @Test
    fun `without the permission access is NOT_GRANTED`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        assertEquals(LocalAccess.NOT_GRANTED, localAccess(context, sdkInt = 33))
    }
}

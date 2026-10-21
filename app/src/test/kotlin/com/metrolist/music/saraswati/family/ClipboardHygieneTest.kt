/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave A (M7) — after the admin key is saved, the clipboard is
// cleared only if it still holds that key. Whatever else the person copied
// stays, and before API 28 (no clearPrimaryClip) nothing is touched.
package com.metrolist.music.saraswati.family

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ClipboardHygieneTest {
    private lateinit var clipboard: ClipboardManager

    @Before
    fun setUp() {
        clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
    }

    private fun copy(text: String) = clipboard.setPrimaryClip(ClipData.newPlainText("test", text))

    private fun clipText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun `a clipboard holding the key is cleared`() {
        copy("the-admin-key")

        assertTrue(ClipboardHygiene.clearIfHolds(clipboard, "the-admin-key"))

        assertNull(clipboard.primaryClip)
    }

    @Test
    fun `surrounding whitespace on the clip does not hide the key`() {
        copy("  the-admin-key\n")

        assertTrue(ClipboardHygiene.clearIfHolds(clipboard, "the-admin-key"))
        assertNull(clipboard.primaryClip)
    }

    @Test
    fun `invisible characters and a wrapped line on the clip do not hide the key`() {
        copy("\uFEFFthe-admin\u200B-key\r\n")

        assertTrue(ClipboardHygiene.clearIfHolds(clipboard, "the-admin-key"))
        assertNull(clipboard.primaryClip)
    }

    @Test
    fun `anything else on the clipboard is left alone`() {
        copy("a recipe the person copied")

        assertFalse(ClipboardHygiene.clearIfHolds(clipboard, "the-admin-key"))
        assertEquals("a recipe the person copied", clipText())
    }

    @Test
    fun `a clip that only contains the key is not the key`() {
        copy("the-admin-key and more")

        assertFalse(ClipboardHygiene.clearIfHolds(clipboard, "the-admin-key"))
        assertEquals("the-admin-key and more", clipText())
    }

    @Test
    fun `an empty clipboard is left alone`() {
        assertFalse(ClipboardHygiene.clearIfHolds(clipboard, "the-admin-key"))
    }

    @Test
    fun `a blank secret never clears anything`() {
        copy("   ")

        assertFalse(ClipboardHygiene.clearIfHolds(clipboard, "  "))
        assertEquals("   ", clipText())
    }

    @Test
    fun `before API 28 nothing is cleared`() {
        copy("the-admin-key")

        assertFalse(ClipboardHygiene.clearIfHolds(clipboard, "the-admin-key", sdkInt = 27))
        assertEquals("the-admin-key", clipText())
    }

    @Test
    fun `API 28 is the first that clears`() {
        copy("the-admin-key")

        assertTrue(ClipboardHygiene.clearIfHolds(clipboard, "the-admin-key", sdkInt = 28))
    }
}

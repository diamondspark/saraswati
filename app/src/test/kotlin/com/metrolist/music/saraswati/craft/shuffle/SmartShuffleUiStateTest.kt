package com.metrolist.music.saraswati.craft.shuffle

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmartShuffleUiStateTest {
    @Test
    fun `the button shows smart while smart is on`() {
        val button = ChaturShuffleUi.buttonState(ShuffleMode.SMART, isListenTogetherGuest = false)

        assertEquals(ChaturLabel.SMART, button.label)
        assertTrue(button.sparkle)
        assertEquals(ShuffleMode.OFF, button.tapTo)
    }

    @Test
    fun `smart picks are marked in the queue`() {
        val pick = SmartShuffle.pick(
            listOf(MediaItem.Builder().setMediaId("p").build()),
            queueIds = emptySet(),
            snoozed = emptySet(),
            count = 1,
        ).single()
        val plain = MediaItem.Builder().setMediaId("q").build()

        assertTrue(pick.isSmartPick())
        assertFalse(plain.isSmartPick())
    }

    @Test
    fun `a Listen Together guest cannot change the mode`() {
        for (mode in ShuffleMode.entries) {
            val button = ChaturShuffleUi.buttonState(mode, isListenTogetherGuest = true)

            assertFalse(button.enabled)
            assertNull(button.tapTo)
        }
    }
}

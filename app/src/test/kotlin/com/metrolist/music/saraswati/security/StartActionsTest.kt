/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the exported-service action blocklist
// (audit NEW-2) — the widget/alarm actions MusicService.onStartCommand
// used to run for any caller must be refused now that the legitimate
// senders (the widget BroadcastReceivers) are gone.
package com.metrolist.music.saraswati.security

import com.metrolist.music.playback.MusicService
import com.metrolist.music.widget.MusicWidgetReceiver
import com.metrolist.music.widget.PlaylistWidgetReceiver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartActionsTest {
    @Test
    fun `the widget like action is blocked`() {
        assertTrue(StartActions.isBlocked(MusicWidgetReceiver.ACTION_LIKE))
    }

    @Test
    fun `the alarm trigger action is blocked`() {
        assertTrue(StartActions.isBlocked(MusicService.ACTION_ALARM_TRIGGER))
    }

    @Test
    fun `other widget and playlist actions are blocked too`() {
        assertTrue(StartActions.isBlocked(MusicWidgetReceiver.ACTION_PLAY_PAUSE))
        assertTrue(StartActions.isBlocked(MusicWidgetReceiver.ACTION_NEXT))
        assertTrue(StartActions.isBlocked(MusicWidgetReceiver.ACTION_PREVIOUS))
        assertTrue(StartActions.isBlocked(MusicWidgetReceiver.ACTION_UPDATE_WIDGET))
        assertTrue(StartActions.isBlocked(PlaylistWidgetReceiver.ACTION_UPDATE_WIDGET))
        assertTrue(StartActions.isBlocked(PlaylistWidgetReceiver.ACTION_PLAY_TARGET))
    }

    @Test
    fun `a media button action is not blocked`() {
        // Intent.ACTION_MEDIA_BUTTON's literal value - avoids pulling android.content.Intent
        // into a plain JVM unit test for a single constant.
        assertFalse(StartActions.isBlocked("android.intent.action.MEDIA_BUTTON"))
    }

    @Test
    fun `a null action is not blocked`() {
        assertFalse(StartActions.isBlocked(null))
    }
}

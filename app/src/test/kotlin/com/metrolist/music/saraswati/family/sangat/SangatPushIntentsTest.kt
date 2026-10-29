/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — notification-type FCM messages are recognised and refused; data messages pass.
package com.metrolist.music.saraswati.family.sangat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SangatPushIntentsTest {
    @Test
    fun `a data message passes`() {
        assertFalse(SangatPushIntents.carriesNotification(listOf("type", "google.message_id", "google.sent_time", "from", "google.c.sender.id"), null))
        assertFalse(SangatPushIntents.carriesNotification(emptyList(), null))
    }

    @Test
    fun `a gcm n key marks a notification`() {
        assertTrue(SangatPushIntents.carriesNotification(listOf("type", "gcm.n.title"), null))
        assertTrue(SangatPushIntents.carriesNotification(listOf("gcm.n.icon"), null))
    }

    @Test
    fun `a gcm notification key marks a notification`() {
        assertTrue(SangatPushIntents.carriesNotification(listOf("gcm.notification.body"), null))
    }

    @Test
    fun `the enabled flag alone marks a notification`() {
        assertTrue(SangatPushIntents.carriesNotification(listOf("type"), "1"))
        assertFalse(SangatPushIntents.carriesNotification(listOf("type"), "0"))
    }

    @Test
    fun `lookalike keys do not`() {
        assertFalse(SangatPushIntents.carriesNotification(listOf("gcm.nx", "gcm.n", "xgcm.n.title", "notification"), null))
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — source-level pins on the hardening no JVM test can drive (the manifest, the Firebase service,
// the notification wiring, the registrar's Saral switch).
package com.metrolist.music.saraswati.family.sangat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SangatPushHardeningSourceTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")
        return file.readText()
    }

    @Test
    fun `notification delegation is switched off in the gms manifest`() {
        val manifest = source("src/gms/AndroidManifest.xml")
        val at = manifest.indexOf("firebase_messaging_notification_delegation_enabled")
        assertTrue(at >= 0)
        assertTrue(manifest.substring(at, manifest.indexOf("/>", at)).contains("android:value=\"false\""))
    }

    @Test
    fun `the service drops notification-type intents before the SDK sees them`() {
        val service = source("src/gms/kotlin/com/metrolist/music/saraswati/family/sangat/SangatPushService.kt")
        val body = service.substring(service.indexOf("override fun handleIntent(intent: Intent)"))
        assertTrue(body.indexOf("SangatPushIntents.carriesNotification(") in 0 until body.indexOf("super.handleIntent(intent)"))
    }

    @Test
    fun `the foreground poll prunes the announced rooms to the live ones`() {
        val notifications = source("src/main/kotlin/com/metrolist/music/saraswati/family/sangat/SangatNotifications.kt")
        val at = notifications.indexOf("watcher.answeredLive.filterNotNull()")
        assertTrue(at >= 0)
        assertTrue(notifications.substring(at, at + 200).contains("announcedRooms.pruneTo("))
    }

    @Test
    fun `the registrar tells the sync whether Saral is active`() {
        val registrar = source("src/gms/kotlin/com/metrolist/music/saraswati/family/sangat/SangatPushRegistrar.kt")
        assertTrue(registrar.contains("isSaral = { SaralActive.on }"))
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave A (M5) — the crash screen's Share button opens a chooser
// to any installed app, a way out of Saral for a grandparent. MainActivity sets
// SaralActive together with the cached role, CrashHandler hands it to
// CrashActivity (which runs in its own process) as an intent extra, and
// CrashActivity then shows one plain Hindi line and a Restart button instead of
// the trace and Share. These tests pin each link of that chain: the flag, the
// extra, and, in the source (there is no Compose UI test here), that the Saral
// screen holds nothing that shares or shows the trace.
package com.metrolist.music.saraswati.saral

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.ui.screens.CrashActivity
import com.metrolist.music.utils.CrashHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CrashHandlerSaralExtraTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SaralActive.on = false
    }

    @After
    fun tearDown() {
        SaralActive.on = false
    }

    @Test
    fun `the flag starts off`() {
        assertFalse(SaralActive.on)
    }

    @Test
    fun `a crash while Saral is on tells the crash screen so`() {
        SaralActive.on = true

        val intent = CrashHandler.crashIntent(context, "the log")

        assertTrue(intent.getBooleanExtra(CrashHandler.EXTRA_SARAL, false))
    }

    // SARASWATI: fix wave B2 — the Saral crash screen shows no trace, so none travels in the intent.
    @Test
    fun `a crash in Saral mode carries no crash log`() {
        SaralActive.on = true

        val intent = CrashHandler.crashIntent(context, "java.lang.IllegalStateException at com.metrolist")

        assertFalse(intent.hasExtra(CrashHandler.EXTRA_CRASH_LOG))
        assertNull(intent.getStringExtra(CrashHandler.EXTRA_CRASH_LOG))
        assertFalse(intent.extras!!.keySet().any { intent.extras!!.get(it)?.toString()?.contains("IllegalStateException") == true })
    }

    @Test
    fun `a crash in the full app still carries the log`() {
        SaralActive.on = false

        assertEquals("the log", CrashHandler.crashIntent(context, "the log").getStringExtra(CrashHandler.EXTRA_CRASH_LOG))
    }

    @Test
    fun `a crash in the full app does not`() {
        SaralActive.on = false

        assertFalse(CrashHandler.crashIntent(context, "the log").getBooleanExtra(CrashHandler.EXTRA_SARAL, true))
    }

    @Test
    fun `the flag follows the role when it is switched back`() {
        SaralActive.on = true
        assertTrue(CrashHandler.crashIntent(context, "x").getBooleanExtra(CrashHandler.EXTRA_SARAL, false))

        SaralActive.on = false
        assertFalse(CrashHandler.crashIntent(context, "x").getBooleanExtra(CrashHandler.EXTRA_SARAL, true))
    }

    @Test
    fun `the crash intent still opens the crash screen in a fresh task`() {
        val intent = CrashHandler.crashIntent(context, "x")

        assertEquals(CrashActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }
}

class SaralCrashSourceTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path from ${File(".").absoluteFile}")
        return file.readText()
    }

    private val mainActivity get() = source("src/main/kotlin/com/metrolist/music/MainActivity.kt")
    private val crashHandler get() = source("src/main/kotlin/com/metrolist/music/utils/CrashHandler.kt")
    private val crashActivity get() = source("src/main/kotlin/com/metrolist/music/ui/screens/CrashActivity.kt")
    private val saralActive get() = source("src/main/kotlin/com/metrolist/music/saraswati/saral/SaralActive.kt")

    @Test
    fun `the flag is volatile, since the crash handler reads it from whatever thread crashed`() {
        assertTrue(Regex("""@Volatile\s+var on: Boolean""").containsMatchIn(saralActive))
    }

    @Test
    fun `MainActivity changes the role, and so the flag, in one place only`() {
        val activity = mainActivity

        val assignments = Regex("""(?<![.\w])grandparentRole\s*=(?!=)""").findAll(activity).map { it.range.first }.toList()
        val setter = activity.indexOf("private fun applyGrandparentRole")
        assertTrue(setter >= 0)
        val setterEnd = activity.indexOf('}', setter)
        assertTrue("the setter also sets the flag", activity.substring(setter, setterEnd).contains("SaralActive.on = value"))
        assertTrue(
            "grandparentRole is assigned only inside applyGrandparentRole (declaration aside)",
            assignments.all { it in setter..setterEnd },
        )
        assertEquals("both the cached read and the live follow go through the setter", 2, Regex("""applyGrandparentRole\(""").findAll(activity).count() - 1)
    }

    @Test
    fun `the application class sets the flag from the cached role right after the crash handler is installed`() {
        val app = source("src/main/kotlin/com/metrolist/music/App.kt")

        val install = app.indexOf("CrashHandler.install(this)")
        val init = app.indexOf("SaralActive.initFrom(dataStore)")
        assertTrue(install >= 0)
        assertTrue("App.onCreate initialises the flag", init >= 0)
        assertTrue("and does it after the handler exists", init > install)
        assertTrue(
            "with nothing but the install and a comment in between",
            app.substring(install, init).lines().drop(1).dropLast(1).all { it.isBlank() || it.trim().startsWith("//") },
        )
        assertTrue("before the scrub, the service and every other startup step", init < app.indexOf("SecretScrubber.scrubPlaintext"))
    }

    @Test
    fun `the crash handler passes the flag to the crash screen`() {
        val handler = crashHandler

        assertTrue(handler.contains("""const val EXTRA_SARAL = "saral_active""""))
        assertTrue(handler.contains("putExtra(EXTRA_SARAL, saral)"))
        assertTrue("the flag is read when the crash happens", handler.contains("saral: Boolean = SaralActive.on"))
        assertTrue("the crash is shown through that intent", handler.contains("startActivity(crashIntent(applicationContext, crashLog))"))
    }

    @Test
    fun `the crash screen reads the extra and shows the Saral screen when it is set`() {
        val activity = crashActivity

        assertTrue(activity.contains("saral = intent.getBooleanExtra(CrashHandler.EXTRA_SARAL, false)"))
        val choice = activity.substringAfter("if (saral) {").substringBefore("} else {")
        assertTrue(choice.contains("SaralCrashScreen(onRestart = ::restartApp)"))
        assertFalse("the Saral branch offers no share", choice.contains("shareCrashLog") || choice.contains("onShare"))
        assertTrue("and the other branch is the usual one", activity.substringAfter("} else {").contains("CrashScreen("))
    }

    @Test
    fun `the Saral crash screen has no trace, no share and no way out but Restart`() {
        val screen = crashActivity.substringAfter("fun SaralCrashScreen")

        for (word in listOf("crashLog", "onShare", "share", "Share", "Intent", "startActivity", "chooser", "Chooser", "onClose")) {
            assertFalse("SaralCrashScreen must not contain $word", screen.contains(word))
        }
        assertTrue(screen.contains("R.string.saral_crash_message"))
        assertTrue(screen.contains("R.string.saral_crash_restart"))
        assertEquals("one button", 1, Regex("""Button\(""").findAll(screen).count())
    }

    @Test
    fun `sharing is refused outright on a Saral phone, whatever calls it`() {
        val share = crashActivity.substringAfter("private fun shareCrashLog")

        assertTrue(share.trimStart().substringAfter("{").trimStart().startsWith("""// SARASWATI: fix wave A (M5) — belt and braces: nothing on the Saral screen calls this.
        if (saral) return"""))
    }

    @Test
    fun `restart relaunches the app in a fresh task and ends the crash screen`() {
        val restart = crashActivity.substringAfter("private fun restartApp").substringBefore("override fun")

        assertTrue(restart.contains("getLaunchIntentForPackage(packageName)"))
        assertTrue(restart.contains("FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK"))
        assertTrue(restart.contains("finishAffinity()"))
    }
}

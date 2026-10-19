/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import com.metrolist.music.BuildConfig
import com.metrolist.music.saraswati.saral.SaralActive
import com.metrolist.music.ui.screens.CrashActivity
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class CrashHandler private constructor(
    private val applicationContext: Context
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val crashLog = buildCrashLog(throwable)
            Timber.e(throwable, "App crashed")
            
            // Launch crash activity
            applicationContext.startActivity(crashIntent(applicationContext, crashLog))
            
            // Kill the current process
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)
        } catch (e: Exception) {
            // If we fail to handle the crash, fall back to default handler
            Timber.e(e, "Error handling crash")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashLog(throwable: Throwable): String {
        val stackTrace = StringWriter().apply {
            throwable.printStackTrace(PrintWriter(this))
        }.toString()

        return buildString {
            // SARASWATI: rebranded from "Metrolist Crash Report" (audit A11-3).
            appendLine("Saraswati Crash Report")
            appendLine("=".repeat(50))
            appendLine()
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Device: ${Build.MODEL}")
            appendLine("Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("=".repeat(50))
            appendLine("Stacktrace:")
            appendLine("=".repeat(50))
            appendLine()
            append(stackTrace)
        }
    }

    companion object {
        const val EXTRA_CRASH_LOG = "crash_log"

        // SARASWATI: fix wave A (M5) — true when Saral was the whole app at the moment of the
        // crash. CrashActivity runs in its own process (":crash"), so this extra is the only
        // way it can know; it then offers no Share and shows no stack trace.
        const val EXTRA_SARAL = "saral_active"

        internal fun crashIntent(
            context: Context,
            crashLog: String,
            saral: Boolean = SaralActive.on,
        ): Intent =
            Intent(context, CrashActivity::class.java).apply {
                putExtra(EXTRA_CRASH_LOG, crashLog)
                putExtra(EXTRA_SARAL, saral)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Timber.d("CrashHandler installed")
        }
    }
}

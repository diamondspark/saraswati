/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 — PackageInstaller delivers its session result here,
// not exported (AndroidManifest.xml), reachable only through the explicit
// PendingIntent InstallSession hands to session.commit(). Two outcomes:
// STATUS_PENDING_USER_ACTION means Android still needs the user to confirm
// the install in a system dialog (EXTRA_INTENT is that dialog's Intent) —
// starting it (FLAG_ACTIVITY_NEW_TASK) is the ORDINARY path for every real
// install and necessarily pauses UpdateActivity, which is exactly why a
// terminal status below is persisted via PendingInstall UNCONDITIONALLY,
// before even trying the live in-memory listener: this receiver is the one
// thing that reliably sees every terminal outcome regardless of whether
// UpdateActivity is resumed, paused, or not running at all (see
// PendingInstall.kt's file header; fix round 1, dedicated review).
package dev.diamondspark.saraswati.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** The single current listener for install session outcomes. Set by UpdateActivity while it's resumed. */
object InstallEvents {
    @Volatile
    var listener: ((statusCode: Int) -> Unit)? = null
}

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = extraIntent(intent) ?: return
            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirmIntent)
            return
        }

        PendingInstall.recordTerminalStatus(PendingInstall.prefs(context), status)
        InstallEvents.listener?.invoke(status)
    }

    @Suppress("DEPRECATION")
    private fun extraIntent(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }
}

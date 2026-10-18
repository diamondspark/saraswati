/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 5 — the one place this app ever touches PackageInstaller.
// The commit PendingIntent is explicit (targets InstallResultReceiver by
// class, no implicit intent-filter match) and mutable (PendingIntent.FLAG_MUTABLE):
// PackageInstaller fills in EXTRA_STATUS/EXTRA_INTENT on the Intent it
// delivers through this PendingIntent, which the platform can only do when
// the PendingIntent is mutable — an immutable one would silently drop those
// extras.
package dev.diamondspark.saraswati.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

class InstallSession(private val context: Context) {

    /** Blocking. Opens, writes and commits a PackageInstaller session for [apkFile]. */
    fun install(apkFile: File, expectedPackageName: String) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(expectedPackageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }

        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("saraswati-update", 0, apkFile.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }

            val intent = Intent(context, InstallResultReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pendingIntent.intentSender)
        }
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 3 — whether this phone may read its own audio files. READ_MEDIA_AUDIO exists only on
// Android 13 and later; below that the only way in is READ_EXTERNAL_STORAGE, which also reads photos and is not
// allowed, so older phones say so instead of asking.
package com.metrolist.music.saraswati.localmedia

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class LocalAccess { GRANTED, NOT_GRANTED, UNSUPPORTED }

const val LOCAL_MIN_SDK = 33

fun localAccess(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
): LocalAccess =
    when {
        sdkInt < LOCAL_MIN_SDK -> LocalAccess.UNSUPPORTED
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
            PackageManager.PERMISSION_GRANTED -> LocalAccess.GRANTED
        else -> LocalAccess.NOT_GRANTED
    }

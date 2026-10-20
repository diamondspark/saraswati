/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave A (M7) — the owner pastes the admin key from the clipboard
// to set the phone up. Once the hub accepted it and it is stored (sealed), the
// copy on the clipboard is a plaintext credential any app that reads the
// clipboard could take, so it is cleared, but only when the clipboard really
// holds that key: whatever else the person copied is theirs.
package com.metrolist.music.saraswati.family

import android.content.ClipboardManager
import android.os.Build

internal object ClipboardHygiene {
    /**
     * Clears the primary clip if one of its items is exactly [secret] (ignoring surrounding whitespace).
     * Only API 28 and later can clear a clip; before that nothing is touched. Never throws. Returns true
     * when it cleared the clip.
     */
    fun clearIfHolds(
        clipboard: ClipboardManager,
        secret: String,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        if (sdkInt < Build.VERSION_CODES.P || secret.isBlank()) return false
        return try {
            val clip = clipboard.primaryClip ?: return false
            val holdsIt = (0 until clip.itemCount).any { clip.getItemAt(it).text?.toString()?.trim() == secret }
            if (holdsIt) clipboard.clearPrimaryClip()
            holdsIt
        } catch (e: Exception) {
            false
        }
    }
}

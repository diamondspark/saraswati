/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 8 — the fixed set a station's icon is picked from. Index 0
// is the radio-waves glyph on every tile of the approved Saral mockup, so a
// station with no choice looks exactly as mocked; the other five are a small
// extra set from the app's own icons. A station stores only the index, so any
// index (the hub accepts any non-negative integer) maps onto a glyph.
package com.metrolist.music.saraswati.family

import androidx.annotation.DrawableRes
import com.metrolist.music.R

object StationIcons {
    private val icons: List<Int> =
        listOf(
            R.drawable.radio,
            R.drawable.music_note,
            R.drawable.favorite,
            R.drawable.album,
            R.drawable.bedtime,
            R.drawable.graphic_eq,
        )

    val COUNT: Int get() = icons.size

    @DrawableRes
    fun drawableFor(index: Int): Int = icons[Math.floorMod(index, icons.size)]
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.res.Configuration
import com.metrolist.music.R
import java.util.Locale
import timber.log.Timber

fun getArtistSeparator(context: Context): String = " ${context.getString(R.string.and)} "

fun <T> List<T>.joinToArtistString(
    conjunction: String,
    transform: (T) -> String,
): String = when (size) {
    0 -> ""
    1 -> transform(this[0])
    2 -> "${transform(this[0])}$conjunction${transform(this[1])}"
    else -> dropLast(1).joinToString(", ") { transform(it) } + "$conjunction${transform(last())}"
}

// SARASWATI (audit A11-1): printStackTrace() always writes to stderr/Logcat,
// release build or not. Routing through Timber instead means the stack
// trace only reaches Logcat in debug builds (App.kt only plants
// Timber.DebugTree when BuildConfig.DEBUG).
fun reportException(throwable: Throwable) {
    Timber.e(throwable)
}

@Suppress("DEPRECATION")
fun setAppLocale(context: Context, locale: Locale) {
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
}

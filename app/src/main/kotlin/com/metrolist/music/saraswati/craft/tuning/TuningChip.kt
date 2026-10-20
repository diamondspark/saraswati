/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.tuning

import com.metrolist.innertube.models.WatchEndpoint

/** One radio tuning chip above "Up next", labelled exactly as YouTube sends it. */
data class TuningChip(
    val label: String,
    val endpoint: WatchEndpoint,
    val selected: Boolean,
)

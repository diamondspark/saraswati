/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.tuning

import com.metrolist.innertube.pages.NextResult

interface RadioTuningApi {
    fun chips(result: NextResult): List<TuningChip>
}

// No chip shape is confirmed yet (Phase 6 Task 18 captures it), so nothing is parsed and the row stays hidden.
object UnconfirmedRadioTuningApi : RadioTuningApi {
    override fun chips(result: NextResult): List<TuningChip> = emptyList()
}

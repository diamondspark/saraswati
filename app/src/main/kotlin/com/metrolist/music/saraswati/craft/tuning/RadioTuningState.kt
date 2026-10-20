/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.tuning

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioTuningState @Inject constructor() {
    private val _chips = MutableStateFlow<List<TuningChip>>(emptyList())
    val chips: StateFlow<List<TuningChip>> = _chips.asStateFlow()

    fun publish(chips: List<TuningChip>) {
        _chips.value = chips
    }

    fun clear() {
        _chips.value = emptyList()
    }
}

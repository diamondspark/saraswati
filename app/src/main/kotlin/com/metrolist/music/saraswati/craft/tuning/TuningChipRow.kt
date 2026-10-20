/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.tuning

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the chip row shows. A Listen Together guest never sees it, because a guest cannot change the queue.
 * [select] is the tuner's select; the row only forwards taps to it.
 */
class TuningChipRowState(
    val chips: StateFlow<List<TuningChip>>,
    private val isListenTogetherGuest: Boolean,
    private val select: suspend (TuningChip) -> TuneResult,
) {
    fun visibleOf(chips: List<TuningChip>): List<TuningChip> = if (isListenTogetherGuest) emptyList() else chips

    suspend fun tap(chip: TuningChip): TuneResult = select(chip)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningChipRow(
    state: TuningChipRowState,
    modifier: Modifier = Modifier,
) {
    val chips by state.chips.collectAsStateWithLifecycle()
    val visible = state.visibleOf(chips)
    if (visible.isEmpty()) return

    val scope = rememberCoroutineScope()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.tuning_row_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier =
                Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visible.forEach { chip ->
                FilterChip(
                    selected = chip.selected,
                    onClick = { scope.launch { state.tap(chip) } },
                    label = { Text(chip.label) },
                )
            }
        }
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 8 — the Home card's state: the mix only while it is for the current window and day, the
// window's name, and play. The clock ticks once a minute so the card follows the window without a restart.
package com.metrolist.music.saraswati.craft.prahar

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class PraharViewModel
    internal constructor(
        mixes: Flow<PraharMix?>,
        clock: Flow<LocalDateTime>,
        private val queues: (PraharMix) -> PraharQueue,
        scope: CoroutineScope,
    ) : ViewModel(scope) {
        @Inject
        constructor(store: PraharStore) : this(
            mixes = store.mix,
            clock = everyMinute(),
            queues = { PraharQueue(it) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )

        /** The mix for the current window and day, or null: the card is hidden. */
        val mix: StateFlow<PraharMix?> =
            combine(mixes, clock) { held, now -> held?.takeIf { CarPraharRow.isCurrent(it, now) } }
                .stateIn(scope, SharingStarted.Eagerly, null)

        /** The window the card is about right now. */
        val window: StateFlow<Prahar> =
            clock.map { Prahar.of(it.toLocalTime()) }.stateIn(scope, SharingStarted.Eagerly, Prahar.of(LocalDateTime.now().toLocalTime()))

        /** Starts the current window's mix as a queue. Does nothing when the card has no mix. */
        fun play(startQueue: (PraharQueue) -> Unit) {
            val current = mix.value ?: return
            startQueue(queues(current))
        }

        private companion object {
            fun everyMinute(): Flow<LocalDateTime> =
                flow {
                    while (true) {
                        emit(LocalDateTime.now())
                        delay(ONE_MINUTE_MS)
                    }
                }

            const val ONE_MINUTE_MS = 60_000L
        }
    }

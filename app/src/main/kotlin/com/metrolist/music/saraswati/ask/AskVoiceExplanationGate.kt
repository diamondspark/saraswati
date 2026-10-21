/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: security-ask-explanation — whether the one-time explanation for Ask-routed voice search (SaralAsk)
// has already been shown, backed by the app's shared DataStore the same way LocalSongsRepository's switch is.
package com.metrolist.music.saraswati.ask

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.HasSeenAskVoiceExplanationKey
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the one-time ask-voice explanation has been shown. Lets SaralViewModel's tests fake it. */
interface AskVoiceExplanationGate {
    suspend fun hasSeen(): Boolean

    suspend fun markSeen()
}

@Singleton
class AskVoiceExplanationStore
    internal constructor(
        private val dataStore: DataStore<Preferences>,
    ) : AskVoiceExplanationGate {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(context.dataStore)

        override suspend fun hasSeen(): Boolean = dataStore.data.map { it[HasSeenAskVoiceExplanationKey] ?: false }.first()

        override suspend fun markSeen() {
            dataStore.edit { it[HasSeenAskVoiceExplanationKey] = true }
        }
    }

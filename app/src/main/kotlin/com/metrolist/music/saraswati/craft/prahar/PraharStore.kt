/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 7 — the last Prahar mix this phone built, kept in DataStore so the car's Home can show it at
// once. cached() never suspends. Damaged storage reads as no mix, never a crash.
package com.metrolist.music.saraswati.craft.prahar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class PraharMix(
    val prahar: Prahar,
    @Serializable(with = LocalDateSerializer::class) val day: LocalDate,
    val builtAt: Long,
    val tracks: List<MixTrack>,
)

class PraharStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    val mix: Flow<PraharMix?> = dataStore.data.map { decode(it[Key]) }

    @Volatile private var held: PraharMix? = null
    private val lock = Any()
    private var settled = false

    init {
        scope.launch {
            val stored =
                try {
                    decode(dataStore.data.first()[Key])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            synchronized(lock) {
                if (!settled) {
                    settled = true
                    held = stored
                }
            }
        }
    }

    suspend fun save(mix: PraharMix) {
        val kept = mix.copy(tracks = mix.tracks.take(PraharMixBuilder.MIX_MAX))
        dataStore.edit { it[Key] = json.encodeToString(PraharMix.serializer(), kept) }
        synchronized(lock) {
            settled = true
            held = kept
        }
    }

    /** The mix held right now, or null. Never suspends and never touches storage. */
    fun cached(): PraharMix? = held

    companion object {
        val Key = stringPreferencesKey("saraswatiPraharMix")
        private val json = Json { ignoreUnknownKeys = true }

        internal fun decode(raw: String?): PraharMix? =
            raw?.let {
                try {
                    json.decodeFromString(PraharMix.serializer(), it).let { mix ->
                        mix.copy(tracks = mix.tracks.take(PraharMixBuilder.MIX_MAX))
                    }
                } catch (e: Exception) {
                    null
                }
            }
    }
}

private object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: LocalDate,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

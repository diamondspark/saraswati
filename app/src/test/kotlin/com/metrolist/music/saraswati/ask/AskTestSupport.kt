/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — fakes shared by the Ask and Saral-ask tests: a scripted hub for one ask, a fixed
// identity, and file-backed stores built the way the app builds them.
package com.metrolist.music.saraswati.ask

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.ParivaarMix
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class FakeAskHub : AskHub {
    data class Call(
        val token: String,
        val question: String,
        val locale: String,
    )

    val calls = CopyOnWriteArrayList<Call>()

    @Volatile var answer: Result<AskReply> = Result.success(AskReply(AskIntent.Answer("ok"), fallback = false))

    @Volatile var delayMs: Long = 0L

    override suspend fun ask(
        sessionToken: String,
        question: String,
        locale: String,
    ): Result<AskReply> {
        calls.add(Call(sessionToken, question, locale))
        if (delayMs > 0) delay(delayMs)
        return answer
    }
}

class FixedIdentity(
    var token: String? = "sess",
) : HubIdentity {
    override suspend fun sessionToken(): String? = token

    override suspend fun deviceId(): String? = if (token == null) null else "dev-1"
}

fun askDataStore(
    scope: CoroutineScope,
    file: File,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

fun askGate(
    scope: CoroutineScope,
    store: DataStore<Preferences>,
    hub: FakeHub = FakeHub(),
    identity: HubIdentity = FixedIdentity(),
): FamilyFeatureGate = FamilyFeatureGate(hub, identity, store, scope)

fun askMixStore(
    scope: CoroutineScope,
    store: DataStore<Preferences>,
): ParivaarMixStore = ParivaarMixStore(store, scope)

fun mixTrack(id: String): MixTrack = MixTrack(id, "Song $id", "Artist", null, 180_000L)

fun savedMix(vararg ids: String) = ParivaarMix(builtAt = 1L, tracks = ids.map(::mixTrack))

val BHAJAN_STATION = Station("s-bhajan", "Bhajans", "seedBhajan01", 0)

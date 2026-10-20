/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 10 — AskExecutor: each intent does exactly its one thing, and anything this phone cannot
// do becomes a search for the question. Sangat opens its section and never starts a room.
package com.metrolist.music.saraswati.ask

import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FakeStationsCache
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.ParivaarMixStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

class FakeAskActions : AskActions {
    val calls = CopyOnWriteArrayList<String>()

    override fun openSearch(query: String) {
        calls.add("search($query)")
    }

    override fun playStation(station: Station) {
        calls.add("station(${station.id})")
    }

    override fun playParivaarMix(tracks: List<MixTrack>) {
        calls.add("mix(${tracks.joinToString { it.videoId }})")
    }

    override fun openSangat() {
        calls.add("sangat")
    }
}

class AskExecutorTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var actions: FakeAskActions
    private lateinit var stations: FakeStationsCache
    private lateinit var mixStore: ParivaarMixStore
    private lateinit var familyHub: FakeHub
    private lateinit var gate: FamilyFeatureGate

    private val question = "kuch bhi"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        actions = FakeAskActions()
        stations = FakeStationsCache(listOf(BHAJAN_STATION))
        val store = askDataStore(scope, folder.newFile("mix.preferences_pb"))
        mixStore = askMixStore(scope, store)
        familyHub = FakeHub()
        gate = askGate(scope, askDataStore(scope, folder.newFile("gate.preferences_pb")), familyHub)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun executor() = AskExecutor(actions, stations, mixStore, gate)

    private fun switchOff(vararg keys: String) =
        runBlocking {
            familyHub.killSwitches = keys.associateWith { false }
            gate.refresh()
        }

    @Test
    fun `search opens search for the query`() {
        assertEquals(ExecResult.Done, executor().execute(AskIntent.Search("bhajan"), question))

        assertEquals(listOf("search(bhajan)"), actions.calls.toList())
    }

    @Test
    fun `a known station plays its radio`() {
        assertEquals(ExecResult.Done, executor().execute(AskIntent.PlayStation(BHAJAN_STATION.id), question))

        assertEquals(listOf("station(${BHAJAN_STATION.id})"), actions.calls.toList())
    }

    @Test
    fun `an unknown station searches the question`() {
        executor().execute(AskIntent.PlayStation("no-such-station"), question)

        assertEquals(listOf("search($question)"), actions.calls.toList())
    }

    @Test
    fun `parivaar_mix plays the cached mix as a ParivaarMixQueue`() =
        runBlocking {
            mixStore.save(savedMix("a1", "b2"))
            mixStore.awaitHydrated()

            executor().execute(AskIntent.ParivaarMix, question)

            assertEquals(listOf("mix(a1, b2)"), actions.calls.toList())
        }

    @Test
    fun `parivaar_mix with the switch off searches instead`() =
        runBlocking {
            mixStore.save(savedMix("a1"))
            switchOff("parivaar_mix")

            executor().execute(AskIntent.ParivaarMix, question)

            assertEquals(listOf("search($question)"), actions.calls.toList())
        }

    @Test
    fun `an empty parivaar_mix searches instead`() {
        executor().execute(AskIntent.ParivaarMix, question)

        assertEquals(listOf("search($question)"), actions.calls.toList())
    }

    @Test
    fun `sangat opens the Sangat section and never starts a room`() {
        assertEquals(ExecResult.Done, executor().execute(AskIntent.Sangat, question))

        assertEquals(listOf("sangat"), actions.calls.toList())
    }

    @Test
    fun `sangat with the switch off searches the question`() {
        switchOff("sangat")

        executor().execute(AskIntent.Sangat, question)

        assertEquals(listOf("search($question)"), actions.calls.toList())
    }

    @Test
    fun `answer shows text and starts nothing`() {
        assertEquals(ExecResult.ShowAnswer("Kal baarish hogi"), executor().execute(AskIntent.Answer("Kal baarish hogi"), question))

        assertEquals(emptyList<String>(), actions.calls.toList())
    }
}

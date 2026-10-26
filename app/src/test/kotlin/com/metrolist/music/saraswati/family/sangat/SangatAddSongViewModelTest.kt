/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 fix wave (Task 19 mockup conformance) — the remote screen's Add a song sheet: search shows
// results, a failed search says so, and a song is added to the room once.
package com.metrolist.music.saraswati.family.sangat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SangatAddSongViewModelTest {
    private lateinit var scope: CoroutineScope
    private val suggested = mutableListOf<SangatCandidate>()
    private var result: Result<List<SangatCandidate>> = Result.success(emptyList())
    private val queries = mutableListOf<String>()

    private fun song(id: String) = SangatCandidate(id, "Song $id", "Artist", null, 180_000, null)

    private val search =
        object : SangatSongSearch {
            override suspend fun search(query: String): Result<List<SangatCandidate>> {
                queries += query
                return result
            }
        }

    private val rooms =
        object : SangatRoomPort {
            override val currentRoomCode: Flow<String?> = MutableStateFlow(null)
            override val remoteController: Flow<Boolean> = MutableStateFlow(true)
            override val remoteNowPlaying: Flow<RemoteNowPlaying?> = MutableStateFlow(null)
            override val roomInfo: Flow<SangatRoomInfo?> = MutableStateFlow(null)

            override fun suggestSong(candidate: SangatCandidate) {
                suggested += candidate
            }

            override fun createRoom(username: String) = Unit

            override fun joinRoom(
                roomCode: String,
                username: String,
            ) = Unit

            override fun leaveRoom() = Unit
        }

    @Before
    fun setUp() {
        scope = CoroutineScope(Job() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun vm() = SangatAddSongViewModel(search, rooms, scope, debounceMs = 10)

    private suspend fun settle(vm: SangatAddSongViewModel) {
        repeat(200) {
            if (vm.state.value !is SangatSearchState.Searching) return
            delay(10)
        }
    }

    @Test
    fun `a short query searches nothing`() =
        runBlocking {
            val vm = vm()
            vm.setQuery("a")
            delay(60)
            assertTrue(queries.isEmpty())
            assertEquals(SangatSearchState.Idle, vm.state.value)
        }

    @Test
    fun `a query shows results and only the last typed query is searched`() =
        runBlocking {
            result = Result.success(listOf(song("1"), song("2")))
            val vm = vm()
            vm.setQuery("lag")
            vm.setQuery("lag jaa")
            settle(vm)
            assertEquals(listOf("lag jaa"), queries)
            assertEquals(SangatSearchState.Results(listOf(song("1"), song("2"))), vm.state.value)
        }

    @Test
    fun `a failed search says so instead of showing nothing`() =
        runBlocking {
            result = Result.failure(java.io.IOException("offline"))
            val vm = vm()
            vm.setQuery("lag jaa")
            settle(vm)
            assertEquals(SangatSearchState.Failed, vm.state.value)
        }

    @Test
    fun `adding suggests the song once and marks it added`() {
        val vm = vm()
        vm.add(song("1"))
        vm.add(song("1"))
        assertEquals(listOf(song("1")), suggested)
        assertEquals(setOf("1"), vm.added.value)
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 20 — the family playlists view model over the real repository, with fakes for the hub
// and YouTube and a file-backed DataStore under the cache.
package com.metrolist.music.saraswati.family.playlists

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.music.saraswati.family.FamilyError
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyPlaylist
import com.metrolist.music.saraswati.family.HubException
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.parivaar.FakeIdentity
import com.metrolist.music.saraswati.family.parivaar.FakePort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun fp(
    id: String,
    playlistId: String,
    by: String,
) = FamilyPlaylist(id, playlistId, "Title $id", by, "2026-10-19T00:00:00Z")

private class FakeHub : FamilyPlaylistsHub {
    var rows: List<FamilyPlaylist> = emptyList()
    var failAdd: Throwable? = null

    override suspend fun list(sessionToken: String): Result<List<FamilyPlaylist>> = Result.success(rows)

    override suspend fun add(
        sessionToken: String,
        playlistId: String,
        title: String,
    ): Result<FamilyPlaylist> {
        failAdd?.let { return Result.failure(it) }
        return Result.success(fp("row-new", playlistId, "dev-me").copy(title = title))
    }

    override suspend fun remove(
        sessionToken: String,
        id: String,
    ): Result<Unit> = Result.success(Unit)
}

class FamilyPlaylistsViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private val hub = FakeHub()
    private val port = FakePort()
    private val identity = FakeIdentity()
    private val features = MutableStateFlow<Map<FamilyFeature, Boolean>>(emptyMap())

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun viewModel(): FamilyPlaylistsViewModel {
        val cache = FamilyPlaylistsCache(dataStore, scope)
        val repository =
            FamilyPlaylistsRepository(
                hub,
                port,
                identity,
                cache,
                UnconfirmedCollaborationApi,
                enabled = { features.value[FamilyFeature.FAMILY_PLAYLISTS] ?: true },
            )
        return FamilyPlaylistsViewModel(repository, identity, features, scope)
    }

    @Test
    fun `madeHere holds only the creator's playlists before collaboration`() =
        runBlocking {
            hub.rows = listOf(fp("mine", "PLmine0000000", "dev-me"), fp("theirs", "PLtheirs000000", "dev-other"))
            val vm = viewModel()
            withTimeout(5_000) { vm.refresh() }
            val mine = withTimeout(5_000) { vm.playlists.first { it.size == 2 }.first { it.id == "mine" } }

            withTimeout(5_000) { vm.addable.first { it.isNotEmpty() } }

            assertEquals(setOf("mine"), vm.madeHere.value)
            assertEquals(listOf(mine), vm.addable.value)
        }

    @Test
    fun `madeHere updates when the device id arrives after the playlists`() =
        runBlocking {
            val slow = CompletableDeferred<String?>()
            val lateIdentity =
                object : HubIdentity {
                    override suspend fun sessionToken() = "session-token"

                    override suspend fun deviceId() = slow.await()
                }
            hub.rows = listOf(fp("mine", "PLmine0000000", "dev-me"))
            val cache = FamilyPlaylistsCache(dataStore, scope)
            val repository =
                FamilyPlaylistsRepository(hub, port, identity, cache, UnconfirmedCollaborationApi, enabled = { true })
            val vm = FamilyPlaylistsViewModel(repository, lateIdentity, features, scope)
            withTimeout(5_000) { vm.refresh() }
            withTimeout(5_000) { vm.playlists.first { it.isNotEmpty() } }

            assertTrue(vm.madeHere.value.isEmpty())
            slow.complete("dev-me")

            assertEquals(setOf("mine"), withTimeout(5_000) { vm.madeHere.first { it.isNotEmpty() } })
        }

    @Test
    fun `a create failure surfaces an error, not a crash`() =
        runBlocking {
            hub.failAdd = HubException(500, "boom")
            val vm = viewModel()

            val result = vm.create("Road trip")

            assertTrue(result.isFailure)
            assertEquals(FamilyError.SERVER, vm.error.value)
            vm.clearError()
            assertNull(vm.error.value)
        }

    @Test
    fun `a blank create name is refused with a message, not a crash`() =
        runBlocking {
            val vm = viewModel()

            val result = vm.create("   ")

            assertTrue(result.isFailure)
            assertNotNull(vm.error.value)
        }

    @Test
    fun `each section is hidden while its switch is off`() =
        runBlocking {
            hub.rows = listOf(fp("mine", "PLmine0000000", "dev-me"))
            features.value = mapOf(FamilyFeature.FAMILY_PLAYLISTS to false, FamilyFeature.PARIVAAR_MIX to true)
            val vm = viewModel()
            withTimeout(5_000) { vm.refresh() }

            assertFalse(vm.visible.value)
            assertTrue(withTimeout(5_000) { vm.playlists.first { it.isEmpty() } }.isEmpty())
            assertTrue(vm.addable.value.isEmpty())
        }
}

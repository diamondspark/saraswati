/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 14 — the family playlists registry against fakes for the hub, YouTube and the
// collaboration seam, with a real file-backed DataStore under the cache.
package com.metrolist.music.saraswati.family.playlists

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.FamilyPlaylistsCacheKey
import com.metrolist.music.saraswati.family.FamilyPlaylist
import com.metrolist.music.saraswati.family.HubException
import com.metrolist.music.saraswati.family.parivaar.FakeIdentity
import com.metrolist.music.saraswati.family.parivaar.FakePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun fp(
    id: String,
    playlistId: String,
    by: String,
    title: String = "Title $id",
) = FamilyPlaylist(id, playlistId, title, by, "2026-10-19T00:00:00Z")

private class FakeFamilyHub : FamilyPlaylistsHub {
    var rows: List<FamilyPlaylist> = emptyList()
    var failList: Throwable? = null
    var failAdd: Throwable? = null
    var failRemove: Throwable? = null
    var listCalls = 0
    val calls = mutableListOf<String>()
    val added = mutableListOf<Triple<String, String, String>>()
    val removed = mutableListOf<Pair<String, String>>()

    override suspend fun list(sessionToken: String): Result<List<FamilyPlaylist>> {
        listCalls++
        calls += "list"
        failList?.let { return Result.failure(it) }
        return Result.success(rows)
    }

    override suspend fun add(
        sessionToken: String,
        playlistId: String,
        title: String,
    ): Result<FamilyPlaylist> {
        calls += "add"
        failAdd?.let { return Result.failure(it) }
        added += Triple(sessionToken, playlistId, title)
        return Result.success(fp("row-${added.size}", playlistId, "dev-me", title))
    }

    override suspend fun remove(
        sessionToken: String,
        id: String,
    ): Result<Unit> {
        calls += "remove"
        failRemove?.let { return Result.failure(it) }
        removed += sessionToken to id
        return Result.success(Unit)
    }
}

private class FakeCollaboration(
    var result: Result<String> = Result.failure(CollaborationUnavailable),
) : CollaborationApi {
    val enabled = mutableListOf<String>()

    override suspend fun enable(playlistId: String): Result<String> {
        enabled += playlistId
        return result
    }

    override suspend fun join(
        playlistId: String,
        invite: String,
    ): Result<Unit> = Result.failure(CollaborationUnavailable)
}

class FamilyPlaylistsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private val hub = FakeFamilyHub()
    private val port = FakePort()
    private val identity = FakeIdentity()
    private val collaboration = FakeCollaboration()
    private var on = true

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun cache() = FamilyPlaylistsCache(dataStore, scope).also { runBlocking { it.hydration.join() } }

    private fun repo(cache: FamilyPlaylistsCache = cache()) =
        FamilyPlaylistsRepository(hub, port, identity, cache, collaboration, enabled = { on })

    @Test
    fun `refresh caches the hub list`() =
        runBlocking {
            hub.rows = listOf(fp("a", "PLaaaaaaaaaa", "dev-me"), fp("b", "PLbbbbbbbbbb", "dev-other"))
            val r = repo()

            r.refresh()

            assertEquals(hub.rows, r.cached())
            assertEquals(hub.rows, r.playlists.first())
            // Survives a restart: a fresh cache over the same storage reads it back.
            assertEquals(hub.rows, repo(cache()).cached())
        }

    @Test
    fun `refresh keeps the cached list when the hub fails or the phone has no session`() =
        runBlocking {
            hub.rows = listOf(fp("a", "PLaaaaaaaaaa", "dev-me"))
            val r = repo()
            r.refresh()

            hub.failList = HubException(503, "off")
            r.refresh()
            assertEquals(hub.rows, r.cached())

            hub.failList = null
            hub.rows = emptyList()
            identity.token = null
            r.refresh()
            assertEquals(1, r.cached().size)
        }

    @Test
    fun `an empty hub list clears the cache`() =
        runBlocking {
            hub.rows = listOf(fp("a", "PLaaaaaaaaaa", "dev-me"))
            val r = repo()
            r.refresh()
            hub.rows = emptyList()

            r.refresh()

            assertTrue(r.cached().isEmpty())
        }

    @Test
    fun `create makes a YouTube playlist, then registers it`() =
        runBlocking {
            val r = repo()

            val made = r.create("  Road trip  ").getOrThrow()

            assertEquals(listOf("Road trip"), port.createdTitles)
            val ytId = port.playlists.keys.single()
            assertEquals(listOf(Triple("session-token", ytId, "Road trip")), hub.added)
            assertEquals(ytId, made.playlistId)
            assertEquals(listOf("create", "add"), port.calls.take(1) + hub.calls)
            assertEquals(listOf(made), r.cached())
            assertEquals(listOf(ytId), collaboration.enabled)
        }

    @Test
    fun `create tolerates a collaboration failure`() =
        runBlocking {
            collaboration.result = Result.failure(CollaborationUnavailable)

            val result = repo().create("Songs")

            assertTrue(result.isSuccess)
            assertEquals(1, hub.added.size)
        }

    @Test
    fun `a hub failure after creating on YouTube deletes the orphan playlist`() =
        runBlocking {
            hub.failAdd = HubException(409, "limit")
            val r = repo()

            val result = r.create("Songs")

            assertTrue(result.isFailure)
            assertEquals(409, (result.exceptionOrNull() as HubException).status)
            assertTrue(port.playlists.isEmpty())
            assertEquals(listOf("create", "delete"), port.calls)
            assertTrue(collaboration.enabled.isEmpty())
            assertTrue(r.cached().isEmpty())
        }

    @Test
    fun `create fails without touching YouTube when YouTube refuses or the phone is not paired`() =
        runBlocking {
            port.failCreate = IllegalStateException("not signed in")
            assertTrue(repo().create("Songs").isFailure)
            assertTrue(hub.calls.isEmpty())

            port.failCreate = null
            identity.token = null
            assertTrue(repo().create("Songs").isFailure)
            assertEquals(listOf("create"), port.calls)
            assertTrue(hub.calls.isEmpty())
        }

    @Test
    fun `create rejects a blank title and cuts a long one to the hub limit`() =
        runBlocking {
            val r = repo()
            assertTrue(r.create("   ").isFailure)
            assertTrue(port.calls.isEmpty())

            r.create("x".repeat(400))
            assertEquals(150, hub.added.single().third.length)
        }

    @Test
    fun `adding to someone else's playlist fails with CollaborationUnavailable`() =
        runBlocking {
            val theirs = fp("a", "PLaaaaaaaaaa", "dev-other")

            val result = repo().addSong(theirs, "video000001")

            assertSame(CollaborationUnavailable, result.exceptionOrNull())
            assertTrue(port.calls.isEmpty())
        }

    @Test
    fun `a song on this phone is never sent to YouTube as a family playlist add`() =
        runBlocking {
            port.seed("PLaaaaaaaaaa")
            val mine = fp("a", "PLaaaaaaaaaa", "dev-me")

            val result = repo().addSong(mine, "local:42")

            assertTrue(result.isFailure)
            assertTrue(port.calls.isEmpty())
        }

    @Test
    fun `the creator can add`() =
        runBlocking {
            port.seed("PLaaaaaaaaaa")
            val mine = fp("a", "PLaaaaaaaaaa", "dev-me")

            val result = repo().addSong(mine, "video000001")

            assertTrue(result.isSuccess)
            assertEquals(listOf("video000001"), port.ids("PLaaaaaaaaaa"))
        }

    @Test
    fun `adding without a device id fails`() =
        runBlocking {
            identity.device = null
            assertTrue(repo().addSong(fp("a", "PLaaaaaaaaaa", "dev-me"), "video000001").isFailure)
            assertTrue(port.calls.isEmpty())
        }

    @Test
    fun `remove deletes the hub row and the cached entry, and leaves YouTube alone`() =
        runBlocking {
            val a = fp("a", "PLaaaaaaaaaa", "dev-me")
            val b = fp("b", "PLbbbbbbbbbb", "dev-me")
            hub.rows = listOf(a, b)
            val r = repo()
            r.refresh()

            assertTrue(r.remove(a).isSuccess)

            assertEquals(listOf("session-token" to "a"), hub.removed)
            assertEquals(listOf(b), r.cached())
            assertTrue(port.calls.isEmpty())
        }

    @Test
    fun `remove by a phone that did not add it keeps the entry`() =
        runBlocking {
            val a = fp("a", "PLaaaaaaaaaa", "dev-other")
            hub.rows = listOf(a)
            hub.failRemove = HubException(403, "not yours")
            val r = repo()
            r.refresh()

            val result = r.remove(a)

            assertEquals(403, (result.exceptionOrNull() as HubException).status)
            assertEquals(listOf(a), r.cached())
        }

    @Test
    fun `remove treats a row the hub no longer has as removed`() =
        runBlocking {
            val a = fp("a", "PLaaaaaaaaaa", "dev-me")
            hub.rows = listOf(a)
            hub.failRemove = HubException(404, "gone")
            val r = repo()
            r.refresh()

            assertTrue(r.remove(a).isSuccess)
            assertTrue(r.cached().isEmpty())
        }

    @Test
    fun `family_playlists off gives an empty list and no hub calls`() =
        runBlocking {
            hub.rows = listOf(fp("a", "PLaaaaaaaaaa", "dev-me"))
            val r = repo()
            r.refresh()
            on = false
            hub.calls.clear()

            r.refresh()

            assertTrue(r.cached().isEmpty())
            assertTrue(r.playlists.first().isEmpty())
            assertTrue(r.create("Songs").isFailure)
            assertTrue(r.addSong(fp("a", "PLaaaaaaaaaa", "dev-me"), "video000001").isFailure)
            assertTrue(r.remove(fp("a", "PLaaaaaaaaaa", "dev-me")).isFailure)
            assertTrue(hub.calls.isEmpty())
            assertTrue(port.calls.isEmpty())
            assertEquals(1, hub.listCalls)

            on = true
            assertFalse(r.cached().isEmpty())
        }

    @Test
    fun `damaged storage reads as no playlists`() =
        runBlocking {
            dataStore.edit { it[FamilyPlaylistsCacheKey] = "{not json" }

            assertTrue(cache().cached().isEmpty())
        }
}

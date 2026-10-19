/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: fix wave B2 — the car's Home tab never waits for YouTube. It
// answers at once from its last good snapshot (memory, and a small file so a
// cold start has it), refreshes in the background with an 8 second limit and
// tells the car to ask again only when the refresh brought something
// different. The timeout and the refresh rules run on a virtual clock.
package com.metrolist.music.saraswati.car

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.music.playback.MusicService
import com.metrolist.music.saraswati.family.VirtualTimeDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

private class MemoryHomeStore(
    var stored: List<MediaItem>? = null,
) : CarHomeStore {
    var writes = 0
    var failWrites = false
    var failReads = false

    override fun read(): List<MediaItem>? {
        if (failReads) throw IOException("unreadable")
        return stored
    }

    override fun write(items: List<MediaItem>) {
        if (failWrites) throw IOException("disk full")
        writes++
        stored = items
    }

    override fun clear() {
        stored = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CarHomeSnapshotTest {
    private lateinit var dispatcher: VirtualTimeDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var store: MemoryHomeStore
    private val seen = mutableListOf<Int>()

    private fun row(
        name: String,
        title: String = "Mix $name",
    ) = carBrowsableMediaItem("${MusicService.YOUTUBE_PLAYLIST}/$name", title, "by $name", Uri.parse("https://img.example/$name.jpg"), MediaMetadata.MEDIA_TYPE_PLAYLIST)

    private val first = listOf(row("a"), row("b"))
    private val second = listOf(row("a"), row("b"), row("c"))

    /** What the next fetch does; each call counts. */
    private var fetchCalls = 0
    private var fetchCancelled = 0
    private var fetchBody: suspend () -> List<MediaItem>? = { first }

    @Before
    fun setUp() {
        dispatcher = VirtualTimeDispatcher()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        store = MemoryHomeStore()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun snapshot(
        store: CarHomeStore = this.store,
        minGap: Long = CarHomeSnapshot.MIN_REFRESH_GAP_MS,
    ): CarHomeSnapshot {
        val snapshot =
            CarHomeSnapshot(
                store = store,
                fetch = {
                    fetchCalls++
                    try {
                        fetchBody()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        fetchCancelled++
                        throw e
                    }
                },
                scope = scope,
                io = dispatcher,
                clock = { dispatcher.currentTime },
                minRefreshGapMs = minGap,
            )
        scope.launch { snapshot.changes.collect { seen.add(it) } }
        dispatcher.runCurrent()
        return snapshot
    }

    private fun ids(items: List<MediaItem>) = items.map { it.mediaId.substringAfter('/') }

    // --- the answer never waits ----------------------------------------------------------------------

    @Test
    fun `with no snapshot yet the answer is empty and comes back at once, however long the fetch takes`() {
        val gate = CompletableDeferred<List<MediaItem>?>()
        fetchBody = { gate.await() }
        val snapshot = snapshot()

        val answer = snapshot.current()
        dispatcher.runCurrent()

        assertEquals(emptyList<MediaItem>(), answer)
        assertEquals("the refresh was started in the background", 1, fetchCalls)
    }

    @Test
    fun `the first ever Home arrives later and the car is told how many rows to expect`() {
        fetchBody = {
            delay(3_000)
            first
        }
        val snapshot = snapshot()
        assertTrue(snapshot.current().isEmpty())

        dispatcher.advanceBy(2_999)
        assertTrue(seen.isEmpty())
        assertTrue(snapshot.current().isEmpty())

        dispatcher.advanceBy(1)

        assertEquals(listOf(2), seen)
        assertEquals(listOf("a", "b"), ids(snapshot.current()))
    }

    @Test
    fun `with a snapshot the answer is the snapshot while the refresh is still running`() {
        store.stored = first
        val gate = CompletableDeferred<List<MediaItem>?>()
        fetchBody = { gate.await() }
        val snapshot = snapshot()

        val answer = snapshot.current()
        dispatcher.runCurrent()

        assertEquals(listOf("a", "b"), ids(answer))
        assertEquals(1, fetchCalls)
        assertTrue("nothing new yet, so the car is not told anything", seen.isEmpty())
    }

    @Test
    fun `a refresh that brings something different replaces the snapshot, saves it and tells the car`() {
        store.stored = first
        fetchBody = { second }
        val snapshot = snapshot()
        snapshot.current()

        dispatcher.runCurrent()

        assertEquals(listOf(3), seen)
        assertEquals(listOf("a", "b", "c"), ids(snapshot.current()))
        assertEquals(1, store.writes)
        assertEquals(listOf("a", "b", "c"), ids(store.stored!!))
    }

    @Test
    fun `a refresh that brings the same Home changes nothing and says nothing`() {
        store.stored = first
        fetchBody = { first.map { row(it.mediaId.substringAfter('/')) } }
        val snapshot = snapshot()
        snapshot.current()

        dispatcher.runCurrent()

        assertTrue(seen.isEmpty())
        assertEquals("nothing rewritten", 0, store.writes)
    }

    // --- the timeout -------------------------------------------------------------------------------

    @Test
    fun `a fetch that is still running after eight seconds is abandoned and the snapshot stays`() {
        store.stored = first
        fetchBody = {
            delay(30_000)
            second
        }
        val snapshot = snapshot()
        snapshot.current()

        dispatcher.advanceBy(CarHomeSnapshot.FETCH_TIMEOUT_MS - 1)
        assertEquals("not yet abandoned", 0, fetchCancelled)
        dispatcher.advanceBy(1)
        dispatcher.advanceBy(60_000)

        assertEquals("the fetch was cancelled at the limit", 1, fetchCancelled)
        assertTrue(seen.isEmpty())
        assertEquals(listOf("a", "b"), ids(snapshot.current()))
        assertEquals(0, store.writes)
    }

    @Test
    fun `the limit is eight seconds`() {
        assertEquals(8_000L, CarHomeSnapshot.FETCH_TIMEOUT_MS)
    }

    @Test
    fun `a fetch that lands just inside the limit is kept`() {
        fetchBody = {
            delay(CarHomeSnapshot.FETCH_TIMEOUT_MS - 1)
            first
        }
        val snapshot = snapshot()
        snapshot.current()

        dispatcher.advanceBy(CarHomeSnapshot.FETCH_TIMEOUT_MS)

        assertEquals(listOf(2), seen)
        assertEquals(listOf("a", "b"), ids(snapshot.current()))
    }

    @Test
    fun `with no snapshot, a fetch that times out leaves the answer empty and does not make the car ask again`() {
        fetchBody = {
            delay(30_000)
            first
        }
        val snapshot = snapshot()
        snapshot.current()

        dispatcher.advanceBy(60_000)

        assertTrue(snapshot.current().isEmpty())
        assertTrue("an empty Home is not news", seen.isEmpty())
    }

    // --- failures never cost a good Home -----------------------------------------------------------

    @Test
    fun `a failed fetch keeps the snapshot and says nothing`() {
        store.stored = first
        fetchBody = { null }
        val snapshot = snapshot()
        snapshot.current()

        dispatcher.runCurrent()

        assertEquals(listOf("a", "b"), ids(snapshot.current()))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a fetch that throws keeps the snapshot and says nothing`() {
        store.stored = first
        fetchBody = { throw IllegalStateException("YouTube changed its answer") }
        val snapshot = snapshot()
        snapshot.current()

        dispatcher.runCurrent()

        assertEquals(listOf("a", "b"), ids(snapshot.current()))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `an empty answer never replaces a good snapshot`() {
        store.stored = first
        fetchBody = { emptyList() }
        val snapshot = snapshot()
        snapshot.current()

        dispatcher.runCurrent()

        assertEquals(listOf("a", "b"), ids(snapshot.current()))
        assertTrue(seen.isEmpty())
        assertEquals(0, store.writes)
    }

    @Test
    fun `a store that cannot be read or written does not break Home`() {
        store.failReads = true
        store.failWrites = true
        fetchBody = { first }
        val snapshot = snapshot()

        assertTrue(snapshot.current().isEmpty())
        dispatcher.runCurrent()

        assertEquals(listOf("a", "b"), ids(snapshot.current()))
        assertEquals("the car is still told, even though saving failed", listOf(2), seen)
    }

    // --- cold start ------------------------------------------------------------------------------------

    @Test
    fun `a new process starts with the saved Home before the network has said anything`() {
        fetchBody = { first }
        val firstRun = snapshot()
        firstRun.current()
        dispatcher.runCurrent()
        assertEquals(1, store.writes)

        val gate = CompletableDeferred<List<MediaItem>?>()
        fetchBody = { gate.await() }
        val restarted = snapshot()

        assertEquals(listOf("a", "b"), ids(restarted.current()))
    }

    @Test
    fun `what comes back from the saved file is a browsable playlist folder with its title and artwork`() {
        val file = File.createTempFile("home", ".json")
        try {
            val files = FileCarHomeStore(file)
            files.write(first)

            val back = files.read()!!

            assertEquals(2, back.size)
            assertEquals("Mix a", back[0].mediaMetadata.title)
            assertEquals("by a", back[0].mediaMetadata.subtitle)
            assertEquals(Uri.parse("https://img.example/a.jpg"), back[0].mediaMetadata.artworkUri)
            assertEquals(true, back[0].mediaMetadata.isBrowsable)
            assertEquals(false, back[0].mediaMetadata.isPlayable)
            assertEquals(MediaMetadata.MEDIA_TYPE_PLAYLIST, back[0].mediaMetadata.mediaType)
        } finally {
            file.delete()
        }
    }

    // --- one at a time, not too often -----------------------------------------------------------------

    @Test
    fun `asking again while a refresh is running does not start a second one`() {
        fetchBody = {
            delay(5_000)
            first
        }
        val snapshot = snapshot()

        snapshot.current()
        snapshot.current()
        snapshot.current()
        dispatcher.runCurrent()

        assertEquals(1, fetchCalls)
    }

    @Test
    fun `a car that keeps asking triggers one refresh a minute`() {
        fetchBody = { first }
        val snapshot = snapshot()

        snapshot.current()
        dispatcher.runCurrent()
        assertEquals(1, fetchCalls)

        dispatcher.advanceBy(CarHomeSnapshot.MIN_REFRESH_GAP_MS - 1)
        snapshot.current()
        dispatcher.runCurrent()
        assertEquals("too soon", 1, fetchCalls)

        dispatcher.advanceBy(1)
        snapshot.current()
        dispatcher.runCurrent()
        assertEquals("a minute after the first", 2, fetchCalls)
    }

    @Test
    fun `a car told to ask again does not loop, because the same Home is not news`() {
        fetchBody = { first }
        val snapshot = snapshot(minGap = 0)

        // The car asks, hears about the new Home, asks again, and so on until nothing changes.
        repeat(5) {
            snapshot.current()
            dispatcher.runCurrent()
        }

        assertEquals("told once, however often it asks", listOf(2), seen)
    }

    // --- signing out (fix wave B3) ----------------------------------------------------------------------

    private fun treeOver(snapshot: CarHomeSnapshot) =
        CarBrowseTree(
            resources = CarResources(string = { "string#$it" }, iconUri = { Uri.parse("icon://$it") }),
            stations = com.metrolist.music.saraswati.family.FakeStationsCache(),
            librarySections = { emptyList() },
            homeShelves = { snapshot.current() },
            familyItems = { emptyList() },
        )

    private fun carHome(tree: CarBrowseTree) = runBlocking { withTimeout(2_000) { tree.childrenOf(MusicService.CAR_HOME) } }!!

    private var registered: CarHomeSnapshot? = null

    private fun register(snapshot: CarHomeSnapshot) {
        registered = snapshot
        CarHomeHolder.register(snapshot)
    }

    @After
    fun unregisterTheSnapshot() {
        registered?.let(CarHomeHolder::unregister)
    }

    @Test
    fun `signing out empties the car's Home and removes the saved Home`() {
        store.stored = first
        val snapshot = snapshot()
        register(snapshot)
        val tree = treeOver(snapshot)
        assertEquals("the snapshot is served", listOf("a", "b"), ids(carHome(tree)))
        dispatcher.runCurrent()
        seen.clear()
        fetchBody = { null }

        CarHomeHolder.forget(store)

        assertNull("the saved Home is gone", store.stored)
        dispatcher.runCurrent()
        assertTrue("the car's Home is empty", carHome(tree).isEmpty())
        assertEquals("and the car is told to ask again", listOf(0), seen)
    }

    @Test
    fun `a refresh that was running when the person signed out cannot bring the old Home back`() {
        store.stored = first
        val gate = CompletableDeferred<List<MediaItem>?>()
        fetchBody = { gate.await() }
        val snapshot = snapshot()
        register(snapshot)
        val tree = treeOver(snapshot)
        assertEquals(listOf("a", "b"), ids(carHome(tree)))
        dispatcher.runCurrent()
        assertEquals("the refresh is under way", 1, fetchCalls)
        seen.clear()

        CarHomeHolder.forget(store)
        gate.complete(second)
        dispatcher.runCurrent()
        dispatcher.advanceBy(CarHomeSnapshot.FETCH_TIMEOUT_MS)

        assertNull("nothing was written after the clear", store.stored)
        assertEquals("nothing was written, however late the refresh landed", 0, store.writes)
        assertEquals("the car was told about the clear and about nothing else", listOf(0), seen)
        assertEquals("the refresh was cancelled at the clear", 1, fetchCancelled)
    }

    @Test
    fun `a refresh that has already fetched when the person signs out is refused at the commit`() {
        store.stored = first
        fetchBody = { second }
        lateinit var snapshot: CarHomeSnapshot
        // The sign-out comes in after the fetch returned and before the commit, when there is nothing left to cancel.
        snapshot =
            CarHomeSnapshot(
                store = store,
                fetch = { fetchBody() },
                scope = scope,
                io = dispatcher,
                clock = { dispatcher.currentTime },
                afterFetch = { snapshot.clear() },
            )
        scope.launch { snapshot.changes.collect { seen.add(it) } }
        dispatcher.runCurrent()
        snapshot.current()
        seen.clear()

        dispatcher.runCurrent()

        assertNull("the clear removed the file and the refresh did not put it back", store.stored)
        assertEquals(0, store.writes)
        assertEquals("only the clear was announced", listOf(0), seen)
    }

    @Test
    fun `after the sign-out a new look starts a fresh refresh at once, and what it brings is kept`() {
        store.stored = first
        fetchBody = { second }
        val snapshot = snapshot(minGap = 60_000)
        snapshot.current()
        dispatcher.runCurrent()
        assertEquals(1, fetchCalls)

        snapshot.clear()
        dispatcher.runCurrent()
        seen.clear()
        assertTrue(snapshot.current().isEmpty())
        dispatcher.runCurrent()

        assertEquals("the minute between refreshes does not hold back the first look after a sign-out", 2, fetchCalls)
        assertEquals(listOf(3), seen)
        assertEquals(listOf("a", "b", "c"), ids(snapshot.current()))
    }

    @Test
    fun `clearing twice, or before anything was loaded, is harmless`() {
        val snapshot = snapshot()

        snapshot.clear()
        snapshot.clear()

        assertTrue(snapshot.current().isEmpty())
    }

    @Test
    fun `a store that will not clear does not stop the memory from being emptied`() {
        store.stored = first
        val snapshot = snapshot()
        register(snapshot)
        assertEquals(2, snapshot.current().size)
        val stubborn =
            object : CarHomeStore {
                override fun read() = null

                override fun write(items: List<MediaItem>) = Unit

                override fun clear() = throw IOException("read-only")
            }

        CarHomeHolder.forget(stubborn)

        assertTrue(snapshot.current().isEmpty())
    }

    // --- through the tree, as the car sees it ---------------------------------------------------------

    @Test
    fun `the car's Home tab answers at once while the fetch is hung`() {
        val hung = CompletableDeferred<List<MediaItem>?>()
        fetchBody = { hung.await() }
        val snapshot = snapshot()
        val tree =
            CarBrowseTree(
                resources = CarResources(string = { "string#$it" }, iconUri = { Uri.parse("icon://$it") }),
                stations = com.metrolist.music.saraswati.family.FakeStationsCache(),
                librarySections = { emptyList() },
                homeShelves = { snapshot.current() },
                familyItems = { emptyList() },
            )

        val answer = runBlocking { withTimeout(2_000) { tree.childrenOf(MusicService.CAR_HOME) } }

        assertEquals(emptyList<MediaItem>(), answer)
        assertEquals(emptyList<MediaItem>(), runBlocking { withTimeout(2_000) { tree.childrenOf(MusicService.YOUTUBE_PLAYLIST) } })
    }
}

class FileCarHomeStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = FileCarHomeStore(File(folder.root, FileCarHomeStore.FILE_NAME))

    private fun row(
        id: String,
        title: String = "Mix $id",
    ) = MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).build())
            .build()

    @Test
    fun `nothing saved reads as nothing`() {
        assertNull(store().read())
    }

    @Test
    fun `what was written reads back, in order`() {
        val store = store()
        store.write(listOf(row("youtube_playlist/a"), row("youtube_playlist/b")))

        assertEquals(listOf("youtube_playlist/a", "youtube_playlist/b"), store.read()!!.map { it.mediaId })
    }

    @Test
    fun `writing again replaces the file and leaves no temporary file behind`() {
        val store = store()
        store.write(listOf(row("youtube_playlist/a")))
        store.write(listOf(row("youtube_playlist/z")))

        assertEquals(listOf("youtube_playlist/z"), store.read()!!.map { it.mediaId })
        assertEquals(listOf(FileCarHomeStore.FILE_NAME), folder.root.list()!!.toList())
    }

    @Test
    fun `a damaged file reads as nothing`() {
        File(folder.root, FileCarHomeStore.FILE_NAME).writeText("{not json")

        assertNull(store().read())
    }

    @Test
    fun `a file that is far too big reads as nothing`() {
        File(folder.root, FileCarHomeStore.FILE_NAME).writeText("x".repeat(600 * 1024))

        assertNull(store().read())
    }

    @Test
    fun `rows that are not YouTube playlists, or have no title, are not believed`() {
        File(folder.root, FileCarHomeStore.FILE_NAME).writeText(
            """[
              {"id":"youtube_playlist/ok","title":"Fine","subtitle":null,"artwork":null,"mediaType":3},
              {"id":"car_stations/0/abcdefghijk","title":"A station","subtitle":null,"artwork":null,"mediaType":3},
              {"id":"playlist/liked","title":"Liked","subtitle":null,"artwork":null,"mediaType":3},
              {"id":"youtube_playlist/untitled","title":"  ","subtitle":null,"artwork":null,"mediaType":3}
            ]""",
        )

        assertEquals(listOf("youtube_playlist/ok"), store().read()!!.map { it.mediaId })
    }

    @Test
    fun `a file with unknown fields still reads`() {
        File(folder.root, FileCarHomeStore.FILE_NAME).writeText(
            """[{"id":"youtube_playlist/ok","title":"Fine","subtitle":null,"artwork":null,"mediaType":3,"future":"field"}]""",
        )

        assertEquals(1, store().read()!!.size)
    }

    @Test
    fun `clearing removes the file`() {
        val store = store()
        store.write(listOf(row("youtube_playlist/a")))

        store.clear()

        assertNull(store.read())
        assertFalse(File(folder.root, FileCarHomeStore.FILE_NAME).exists())
    }
}

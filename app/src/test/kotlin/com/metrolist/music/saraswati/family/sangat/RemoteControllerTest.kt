/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — remote-controller mode for car passengers. The phone shows the room and adds
// songs but never plays them: the fake player below records every call a guest handler could make on it, and
// a remote controller's events must reach none of them except the single pause on joining.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.listentogether.ConnectionState
import com.metrolist.music.listentogether.ListenTogetherEvent
import com.metrolist.music.listentogether.PlaybackActionPayload
import com.metrolist.music.listentogether.PlaybackActions
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.listentogether.RoomState
import com.metrolist.music.listentogether.SyncStatePayload
import com.metrolist.music.listentogether.TrackInfo
import com.metrolist.music.listentogether.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteControllerTest {
    private fun track(id: String) = TrackInfo(id = id, title = id, artist = "Artist", duration = 1L)

    private fun room(
        current: TrackInfo? = track("a"),
        playing: Boolean = true,
        queue: List<TrackInfo> = emptyList(),
    ) = RoomState(
        roomCode = "ABC123",
        hostId = "host",
        users = listOf(UserInfo("host", "Papa", true), UserInfo("me", "Mohit", false)),
        currentTrack = current,
        isPlaying = playing,
        position = 0L,
        lastUpdate = 1L,
        queue = queue,
    )

    /** What a guest handler could do to the phone's own player. */
    private class FakePlayer {
        val calls = mutableListOf<String>()

        fun play() = calls.add("play")

        fun pause() = calls.add("pause")

        fun seekTo() = calls.add("seekTo")

        fun setMediaItems() = calls.add("setMediaItems")
    }

    private val roomState = MutableStateFlow<RoomState?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val persisted = mutableListOf<Boolean>()
    private val mode = RemoteControllerMode(scope, roomState) { persisted += it }
    private val player = FakePlayer()
    private var host = false

    /** The manager's guest handlers, as the router sees them: any call here drives the local player. */
    private val router =
        RemoteEventRouter(mode, isHost = { host }, pauseLocalPlayback = { player.pause() })

    /** What the manager does with an event the router lets through: a guest or host handler drives the player. */
    private fun deliver(event: ListenTogetherEvent): Boolean {
        val consumed = router.route(event)
        if (!consumed) player.play()
        return consumed
    }

    private val guestEvents: List<ListenTogetherEvent> =
        listOf(
            ListenTogetherEvent.JoinApproved("ABC123", "me", room()),
            ListenTogetherEvent.PlaybackSync(PlaybackActionPayload(action = PlaybackActions.PLAY, position = 5L)),
            ListenTogetherEvent.PlaybackSync(PlaybackActionPayload(action = PlaybackActions.SEEK, position = 9L)),
            ListenTogetherEvent.PlaybackSync(PlaybackActionPayload(action = PlaybackActions.CHANGE_TRACK, trackInfo = track("b"))),
            ListenTogetherEvent.PlaybackSync(PlaybackActionPayload(action = PlaybackActions.QUEUE_ADD, trackInfo = track("c"))),
            ListenTogetherEvent.PlaybackSync(PlaybackActionPayload(action = PlaybackActions.QUEUE_REMOVE, trackId = "c")),
            ListenTogetherEvent.PlaybackSync(PlaybackActionPayload(action = PlaybackActions.QUEUE_CLEAR)),
            ListenTogetherEvent.SyncStateReceived(SyncStatePayload(track("a"), true, 0L, 1L, emptyList(), 1f, 0L)),
            ListenTogetherEvent.Reconnected("ABC123", "me", room(), isHost = false),
        )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a remote controller never calls play, pause, seek or setMediaItems on the local player for join_approved, sync_playback, sync_state or queue ops`() {
        mode.enter()

        val drivingEvents = guestEvents.filterNot { it is ListenTogetherEvent.JoinApproved }
        drivingEvents.forEach { assertTrue("$it is swallowed", deliver(it)) }
        assertEquals("nothing reached the player", emptyList<String>(), player.calls)

        assertTrue(deliver(guestEvents.first()))
        assertEquals("the join's only touch is the one pause", listOf("pause"), player.calls)
    }

    @Test
    fun `a remote controller pauses local playback once on joining`() {
        mode.enter()
        router.route(ListenTogetherEvent.JoinApproved("ABC123", "me", room()))
        router.route(ListenTogetherEvent.PlaybackSync(PlaybackActionPayload(action = PlaybackActions.PLAY, position = 5L)))
        router.route(ListenTogetherEvent.Reconnected("ABC123", "me", room(), isHost = false))
        assertEquals(listOf("pause"), player.calls)
    }

    @Test
    fun `a remote controller's remoteNowPlaying follows the room`() {
        assertNull("nothing before joining", mode.nowPlaying.value)
        mode.enter()
        assertNull("no room yet", mode.nowPlaying.value)

        roomState.value = room(current = track("a"), playing = true, queue = listOf(track("b")))
        assertEquals(RemoteNowPlaying(track("a"), true, listOf(track("b"))), mode.nowPlaying.value)

        roomState.value = room(current = track("b"), playing = false, queue = listOf(track("c"), track("d")))
        assertEquals(RemoteNowPlaying(track("b"), false, listOf(track("c"), track("d"))), mode.nowPlaying.value)

        roomState.value = room(current = null, playing = false)
        assertEquals(RemoteNowPlaying(null, false, emptyList()), mode.nowPlaying.value)

        roomState.value = null
        assertNull("the room ended", mode.nowPlaying.value)
    }

    @Test
    fun `a normal guest still drives the local player`() {
        assertFalse(mode.enabled.value)
        assertTrue(RemoteControllerPolicy.drivesLocalPlayer(isHost = false, remoteController = false))
        guestEvents.forEach { assertFalse("$it is the manager's to handle", deliver(it)) }
        assertEquals("every event reached the player's handlers", guestEvents.size, player.calls.count { it == "play" })
        roomState.value = room()
        assertNull("a normal guest has no remote view", mode.nowPlaying.value)
    }

    @Test
    fun `the host always does`() {
        assertTrue(RemoteControllerPolicy.drivesLocalPlayer(isHost = true, remoteController = false))
        assertTrue(RemoteControllerPolicy.drivesLocalPlayer(isHost = true, remoteController = true))
        assertFalse(RemoteControllerPolicy.drivesLocalPlayer(isHost = false, remoteController = true))

        mode.enter()
        host = true
        guestEvents.filterNot { it is ListenTogetherEvent.Reconnected }.forEach {
            assertFalse("$it reaches the host handlers", deliver(it))
        }
        val reconnectedAsHost = ListenTogetherEvent.Reconnected("ABC123", "me", room(), isHost = true)
        assertFalse(deliver(reconnectedAsHost))
        assertEquals("the host's events all reached the player", guestEvents.size, player.calls.count { it == "play" })
    }

    @Test
    fun `leaving clears remote-controller mode`() {
        mode.enter()
        roomState.value = room()
        assertTrue(mode.enabled.value)
        assertEquals(listOf(true), persisted)

        mode.clear()

        assertFalse(mode.enabled.value)
        assertNull(mode.nowPlaying.value)
        assertEquals("the flag no longer outlives the room across a restart", listOf(true, false), persisted)
        assertFalse(router.route(guestEvents[1]))
    }

    @Test
    fun `a restart mid-room restores the mode and never overrides a fresh choice`() {
        mode.restore()
        assertTrue(mode.enabled.value)
        assertEquals("restoring writes nothing back", emptyList<Boolean>(), persisted)

        mode.clear()
        mode.restore()
        assertFalse("a late restore is a stale read of a session already left", mode.enabled.value)
    }

    @Test
    fun `every player-touching guest path in the manager consults the policy`() {
        val manager = source("src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherManager.kt")
        val handleEvent = manager.substringAfter("private fun handleEvent(event: ListenTogetherEvent) {").substringBefore("when (event)")
        assertTrue(
            "handleEvent returns when the remote filter consumed the event",
            handleEvent.contains("if (remoteEventRouter.route(event)) return"),
        )
        assertTrue(manager.contains("RemoteControllerPolicy.resolveJoinRemote(roomCode, sangatWatcher.liveRooms.value, sangatWatcher.listIsFresh())"))
        val volume = manager.substringAfter("private fun applyHostVolumeIfNeeded").substringBefore("\n        }\n")
        assertTrue("the host's volume is not applied to a remote controller", volume.contains("drivesLocalPlayer"))
        val buffer = manager.substringAfter("is ListenTogetherEvent.BufferComplete ->").substringBefore("is ListenTogetherEvent.SyncStateReceived")
        assertTrue(buffer.contains("remoteMode.drives("))
        val leave = manager.substringAfter("fun leaveRoom()").substringBefore("\n        }\n")
        assertTrue(leave.contains("remoteMode.clear()"))
        assertTrue("a leave stops a join still waiting on the lookup", leave.contains("joinTickets.cancel()"))
        assertTrue("the join runs only while its ticket is current", manager.contains("joinTickets.runIfCurrent("))
    }

    // SARASWATI: Phase 4 Task 15 — a host must never be a remote controller.
    @Test
    fun `a remote controller made host by the room leaves it`() {
        val madeHost = ListenTogetherEvent.HostChanged("me", "Mohit")
        val reconnectedAsHost = ListenTogetherEvent.Reconnected("ABC123", "me", room(), isHost = true)

        assertTrue(RemoteHostGuard.mustLeave(madeHost, "me", remoteController = true))
        assertTrue(RemoteHostGuard.mustLeave(reconnectedAsHost, "me", remoteController = true))
    }

    @Test
    fun `the guard leaves everyone else alone`() {
        assertFalse("someone else became host", RemoteHostGuard.mustLeave(ListenTogetherEvent.HostChanged("other", "Papa"), "me", true))
        assertFalse("a normal guest may host", RemoteHostGuard.mustLeave(ListenTogetherEvent.HostChanged("me", "Mohit"), "me", false))
        assertFalse("unknown user id", RemoteHostGuard.mustLeave(ListenTogetherEvent.HostChanged("me", "Mohit"), null, true))
        assertFalse(RemoteHostGuard.mustLeave(ListenTogetherEvent.Reconnected("ABC123", "me", room(), isHost = false), "me", true))
        assertFalse(RemoteHostGuard.mustLeave(ListenTogetherEvent.Kicked("x"), "me", true))
    }

    @Test
    fun `the manager asks the guard before anything else`() {
        val manager = source("src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherManager.kt")
        val handleEvent = manager.substringAfter("private fun handleEvent(event: ListenTogetherEvent) {").substringBefore("when (event)")

        assertTrue(handleEvent.indexOf("RemoteHostGuard.consume") in 0 until handleEvent.indexOf("remoteEventRouter.route(event)"))
    }

    // SARASWATI: Phase 4 review fix (1)/(7) — HostChanged for a remote controller.
    @Test
    fun `a remote controller made host is told to leave and never reaches the host handlers`() {
        mode.enter()
        val madeHost = ListenTogetherEvent.HostChanged("me", "Mohit")

        assertTrue("the guard fires before the router", RemoteHostGuard.mustLeave(madeHost, "me", mode.enabled.value))
        assertFalse("as a non-host passenger it still drives nothing", mode.drives(isHost = false))
    }

    @Test
    fun `another member becoming host leaves a remote controller as it was`() {
        mode.enter()
        val other = ListenTogetherEvent.HostChanged("other", "Papa")

        assertFalse(RemoteHostGuard.mustLeave(other, "me", mode.enabled.value))
        assertFalse("the handler's lost-host and gained-host branches both skip a non-host passenger", deliver(other))
        assertTrue(mode.enabled.value)
    }

    // SARASWATI: final fix wave (M9) — what the manager calls, run for real instead of read as source text.
    @Test
    fun `a remote controller made host leaves once and the event goes no further`() {
        var leaves = 0
        val madeHost = ListenTogetherEvent.HostChanged("me", "Asha")
        val reconnectedAsHost = ListenTogetherEvent.Reconnected("ABC123", "me", room(), true)

        assertTrue(RemoteHostGuard.consume(madeHost, "me", true) { leaves++ })
        assertTrue(RemoteHostGuard.consume(reconnectedAsHost, "me", true) { leaves++ })
        assertEquals(2, leaves)
    }

    @Test
    fun `an event that is not about this remote controller is left to the manager`() {
        var leaves = 0
        val others =
            listOf(
                ListenTogetherEvent.HostChanged("other", "Papa"),
                ListenTogetherEvent.Reconnected("ABC123", "me", room(), false),
                ListenTogetherEvent.UserJoined("u2", "Dadi"),
            )

        others.forEach { assertFalse("$it", RemoteHostGuard.consume(it, "me", true) { leaves++ }) }
        // A phone that is not a remote controller keeps the host role it is given.
        assertFalse(RemoteHostGuard.consume(ListenTogetherEvent.HostChanged("me", "Asha"), "me", false) { leaves++ })
        assertEquals(0, leaves)
    }

    @Test
    fun `host services start for a player that became host and never for a remote controller`() {
        assertTrue(RemoteHostGuard.startsHostServices(RoomRole.GUEST, RoomRole.HOST, remoteController = false))
        assertTrue(RemoteHostGuard.startsHostServices(RoomRole.NONE, RoomRole.HOST, remoteController = false))
        assertFalse(RemoteHostGuard.startsHostServices(RoomRole.GUEST, RoomRole.HOST, remoteController = true))
        assertFalse(RemoteHostGuard.startsHostServices(RoomRole.HOST, RoomRole.HOST, remoteController = false))
        assertFalse(RemoteHostGuard.startsHostServices(RoomRole.HOST, RoomRole.GUEST, remoteController = false))
    }

    // SARASWATI: Phase 4 review fix (2)
    @Test
    fun `events wait for the stored flag before a rejoin is routed`() {
        var released = false
        val waiter = scope.launch {
            mode.awaitRestored()
            released = true
        }
        assertFalse("nothing is released while the stored flag is unread", released)

        mode.restore()

        assertTrue(released)
        assertTrue(mode.enabled.value)
        waiter.cancel()
    }

    @Test
    fun `an empty or unreadable stored flag also releases the wait, and so does a choice of this process`() {
        for (release in listOf<(RemoteControllerMode) -> Unit>({ it.restoredNothing() }, { it.enter() }, { it.clear() })) {
            val fresh = RemoteControllerMode(scope, roomState)
            var released = false
            scope.launch {
                fresh.awaitRestored()
                released = true
            }
            assertFalse(released)
            release(fresh)
            assertTrue(released)
        }
    }

    @Test
    fun `the manager awaits the restored flag before it handles a join or a reconnect, and the store always reports back`() {
        val manager = source("src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherManager.kt")
        val collector = manager.substringAfter("Received event: \$event").substringBefore("handleEvent(event)")
        assertTrue(collector.contains("remoteMode.awaitRestored()"))
        val store = source("src/main/kotlin/com/metrolist/music/saraswati/family/sangat/RemoteControllerStore.kt")
        assertEquals("restored, empty, and failed reads all report", 2, Regex("restoredNothing\\(\\)").findAll(store).count())
    }

    // SARASWATI: Phase 4 review fix (8)
    @Test
    fun `a guest whose connection is halted can play its own music`() {
        assertTrue(GuestPlaybackLock.blocks(isInRoom = true, isHost = false, connection = ConnectionState.CONNECTED))
        assertTrue(GuestPlaybackLock.blocks(isInRoom = true, isHost = false, connection = ConnectionState.RECONNECTING))
        assertFalse(GuestPlaybackLock.blocks(isInRoom = true, isHost = false, connection = ConnectionState.ERROR))
        assertFalse(GuestPlaybackLock.blocks(isInRoom = true, isHost = true, connection = ConnectionState.CONNECTED))
        assertFalse(GuestPlaybackLock.blocks(isInRoom = false, isHost = false, connection = ConnectionState.CONNECTED))
    }

    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")
        return file.readText()
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — remote-controller mode for car passengers. The phone shows the room and adds
// songs but never plays them: the fake player below records every call a guest handler could make on it, and
// a remote controller's events must reach none of them except the single pause on joining.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.listentogether.ListenTogetherEvent
import com.metrolist.music.listentogether.PlaybackActionPayload
import com.metrolist.music.listentogether.PlaybackActions
import com.metrolist.music.listentogether.RoomState
import com.metrolist.music.listentogether.SyncStatePayload
import com.metrolist.music.listentogether.TrackInfo
import com.metrolist.music.listentogether.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
        drivingEvents.forEach { assertTrue("$it is swallowed", router.route(it)) }
        assertEquals("nothing reached the player", emptyList<String>(), player.calls)

        assertTrue(router.route(guestEvents.first()))
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
        guestEvents.forEach { assertFalse("$it is the manager's to handle", router.route(it)) }
        assertEquals(emptyList<String>(), player.calls)
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
            assertFalse("$it reaches the host handlers", router.route(it))
        }
        assertEquals(emptyList<String>(), player.calls)
        val reconnectedAsHost = ListenTogetherEvent.Reconnected("ABC123", "me", room(), isHost = true)
        assertFalse(router.route(reconnectedAsHost))
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
        assertTrue("handleEvent routes through the remote filter first", handleEvent.contains("remoteEventRouter.route(event)"))
        assertTrue(manager.contains("remoteController: Boolean = false"))
        val volume = manager.substringAfter("private fun applyHostVolumeIfNeeded").substringBefore("\n        }\n")
        assertTrue("the host's volume is not applied to a remote controller", volume.contains("drivesLocalPlayer"))
        val buffer = manager.substringAfter("is ListenTogetherEvent.BufferComplete ->").substringBefore("is ListenTogetherEvent.SyncStateReceived")
        assertTrue(buffer.contains("remoteMode.drives("))
        val leave = manager.substringAfter("fun leaveRoom()").substringBefore("\n        }\n")
        assertTrue(leave.contains("remoteMode.clear()"))
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

        assertTrue(handleEvent.indexOf("RemoteHostGuard.mustLeave") in 0 until handleEvent.indexOf("remoteEventRouter.route(event)"))
    }

    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")
        return file.readText()
    }
}

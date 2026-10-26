/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — SangatViewModel's rules on fake ports: a normal start, a car join as a remote
// controller, the prompt that is never shown twice, everything hidden while Sangat is off, and the one-time name.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.LiveSangatRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SangatViewModelTest {
    private lateinit var scope: CoroutineScope
    private lateinit var rooms: MutableStateFlow<List<LiveSangatRoom>>
    private lateinit var on: MutableStateFlow<Boolean>
    private lateinit var port: FakePort
    private lateinit var names: FakeNames
    private lateinit var dismissals: SangatDismissals

    private class FakePort : SangatRoomPort {
        val here = MutableStateFlow<String?>(null)
        val car = MutableStateFlow(false)
        val now = MutableStateFlow<RemoteNowPlaying?>(null)
        val info = MutableStateFlow<SangatRoomInfo?>(null)
        val suggested = mutableListOf<SangatCandidate>()
        val created = mutableListOf<String>()
        val joined = mutableListOf<Pair<String, String>>()
        var left = 0

        override val currentRoomCode: Flow<String?> get() = here
        override val remoteController: Flow<Boolean> get() = car
        override val remoteNowPlaying: Flow<RemoteNowPlaying?> get() = now
        override val roomInfo: Flow<SangatRoomInfo?> get() = info

        override fun suggestSong(candidate: SangatCandidate) {
            suggested += candidate
        }

        override fun createRoom(username: String) {
            created += username
        }

        override fun joinRoom(
            roomCode: String,
            username: String,
        ) {
            joined += roomCode to username
        }

        override fun leaveRoom() {
            left++
        }
    }

    private class FakeNames(
        var name: String,
    ) : SangatNames {
        var saves = 0

        override suspend fun saved(): String = name

        override suspend fun save(name: String) {
            this.name = name
            saves++
        }
    }

    private class FakeFeed(
        override val liveRooms: Flow<List<LiveSangatRoom>>,
        override val sangatOn: Flow<Boolean>,
    ) : SangatFeed

    @Before
    fun setUp() {
        scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        rooms = MutableStateFlow(emptyList())
        on = MutableStateFlow(true)
        port = FakePort()
        names = FakeNames(name = "Asha")
        dismissals = SangatDismissals()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newVm(): SangatViewModel =
        SangatViewModel(FakeFeed(rooms, on), port, names, dismissals, scope)

    private fun room(
        code: String,
        car: Boolean = false,
    ) = LiveSangatRoom(
        roomCode = code,
        hostName = "Papa",
        hostDeviceId = "device-$code",
        carMode = car,
        memberCount = 2,
        startedAt = "2026-10-19T10:00:00Z",
    )

    @Test
    fun `start hosts a normal room`() {
        val vm = newVm()

        vm.start()

        assertEquals(listOf("Asha"), port.created)
        assertTrue(port.joined.isEmpty())
    }

    @Test
    fun `joining a car room joins that room by its code`() {
        val vm = newVm()

        vm.join(room("CAR1", car = true))

        assertEquals(listOf("CAR1" to "Asha"), port.joined)
    }

    @Test
    fun `joining a normal room joins that room by its code`() {
        val vm = newVm()

        vm.join(room("NORM1"))

        assertEquals(listOf("NORM1" to "Asha"), port.joined)
    }

    @Test
    fun `a dismissed prompt never shows again for that room`() {
        rooms.value = listOf(room("CAR1", car = true))
        val vm = newVm()
        assertEquals("CAR1", vm.prompt.value?.roomCode)

        vm.dismissPrompt("CAR1")
        assertNull(vm.prompt.value)

        rooms.value = emptyList()
        rooms.value = listOf(room("CAR1", car = true))
        assertNull(vm.prompt.value)
        assertNull(newVm().prompt.value)
    }

    @Test
    fun `everything is hidden while sangat is off`() {
        rooms.value = listOf(room("CAR1", car = true), room("NORM1"))
        on.value = false
        val vm = newVm()

        assertFalse(vm.sangatOn.value)
        assertTrue(vm.live.value.isEmpty())
        assertNull(vm.prompt.value)

        on.value = true
        assertEquals(2, vm.live.value.size)
    }

    @Test
    fun `a blank username asks for one once on the phone`() {
        names.name = ""
        val vm = newVm()

        vm.start()
        assertTrue(port.created.isEmpty())
        assertTrue(vm.askName.value)

        vm.submitName("  Ravi ")
        assertEquals(listOf("Ravi"), port.created)
        assertEquals("Ravi", names.name)
        assertEquals(1, names.saves)
        assertFalse(vm.askName.value)

        vm.start()
        assertEquals(listOf("Ravi", "Ravi"), port.created)
        assertFalse(vm.askName.value)
        assertEquals(1, names.saves)
    }

    @Test
    fun `not now leaves a note that show again takes back`() {
        rooms.value = listOf(room("CAR1", car = true))
        val vm = newVm()

        vm.dismissPrompt("CAR1")
        assertEquals("CAR1", vm.dismissedNote.value)
        assertNull(vm.prompt.value)

        vm.showPromptAgain("CAR1")
        assertNull(vm.dismissedNote.value)
        assertEquals("CAR1", vm.prompt.value?.roomCode)
    }

    @Test
    fun `the banner follows the room this phone is in`() {
        rooms.value = listOf(room("NORM1"))
        val vm = newVm()
        assertEquals(false, vm.banner.value?.joined)

        port.info.value = info("NORM1", host = false)
        assertEquals(true, vm.banner.value?.joined)
        assertEquals(listOf("Papa"), vm.banner.value?.syncedWith)

        port.info.value = info("MINE", host = true)
        assertNull(vm.banner.value)
        assertTrue(vm.rows.value.none { it.room.roomCode == "MINE" })
    }

    private fun info(
        code: String,
        host: Boolean,
    ) = SangatRoomInfo(
        roomCode = code,
        hostName = if (host) "Asha" else "Papa",
        isHost = host,
        members = listOf(SangatMember(if (host) "Asha" else "Papa", isMe = host)) + if (host) emptyList() else listOf(SangatMember("Asha", isMe = true)),
        track = null,
        isPlaying = false,
        positionMs = 0L,
    )
}

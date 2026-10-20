/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 15 — the car's Sangat toggle against a fake room and a fake hub.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.HubIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CarSangatControllerTest {
    private class FakeRoom : CarSangatRoom {
        val mutableState = MutableStateFlow(CarSangatState.IDLE)
        override val state: StateFlow<CarSangatState> = mutableState
        val created = mutableListOf<String>()
        val events = mutableListOf<String>()
        var code: String? = "ABCD1234"
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun createRoom(username: String): String? {
            created += username
            events += "create"
            gate?.await()
            return code?.also { mutableState.value = CarSangatState(true, 0) }
        }

        override fun leave() {
            events += "leave"
            mutableState.value = CarSangatState.IDLE
        }
    }

    private class FakeHub(
        val events: MutableList<String>,
        var succeed: Boolean = true,
    ) : SangatHubApi {
        val marks = mutableListOf<String>()

        override suspend fun getLiveSangat(sessionToken: String) = Result.success(emptyList<com.metrolist.music.saraswati.family.LiveSangatRoom>())

        override suspend fun setSangatCarMode(
            sessionToken: String,
            roomCode: String,
            carMode: Boolean,
        ): Result<Unit> {
            events += "mark"
            marks += "$sessionToken/$roomCode/$carMode"
            return if (succeed) Result.success(Unit) else Result.failure(IllegalStateException("no"))
        }
    }

    private var token: String? = "sess"
    private val room = FakeRoom()
    private val hub = FakeHub(room.events)
    private val identity =
        object : HubIdentity {
            override suspend fun sessionToken() = token

            override suspend fun deviceId() = "me"
        }
    private var sangatOn = true
    private val carMode = SangatCarMode(hub, identity)

    private fun controller() = CarSangatController(room, carMode, identity, { it != FamilyFeature.SANGAT || sangatOn }, { "Papa's phone" })

    private fun toggle(c: CarSangatController = controller()) = runBlocking { withTimeout(5_000) { c.toggle() } }

    @Test
    fun `toggle creates a room and marks it car mode`() {
        assertEquals(CarSangatResult.Started, toggle())

        assertEquals(listOf("Papa's phone"), room.created)
        assertEquals(listOf("sess/ABCD1234/true"), hub.marks)
        assertTrue(carMode.active.value)
    }

    @Test
    fun `the room is marked only after this phone is its host`() {
        toggle()

        // createRoom answers only once the role is HOST (the real room waits for it); the mark comes after.
        assertEquals(listOf("create", "mark"), room.events)
        assertTrue(room.state.value.hosting)
    }

    @Test
    fun `toggle without a session token returns PairRequired and creates nothing`() {
        token = null

        assertEquals(CarSangatResult.PairRequired, toggle())

        assertTrue(room.created.isEmpty())
        assertTrue(hub.marks.isEmpty())
    }

    @Test
    fun `toggle while sangat is off returns Disabled`() {
        sangatOn = false

        assertEquals(CarSangatResult.Disabled, toggle())

        assertTrue(room.created.isEmpty())
    }

    @Test
    fun `toggle while hosting leaves the room`() {
        val c = controller()
        toggle(c)

        assertEquals(CarSangatResult.Ended, toggle(c))

        assertEquals(listOf("create", "mark", "leave"), room.events)
        assertFalse(room.state.value.hosting)
    }

    @Test
    fun `a room that never came up is NoConnection and is not marked`() {
        room.code = null

        assertEquals(CarSangatResult.NoConnection, toggle())

        assertTrue(hub.marks.isEmpty())
        assertFalse(carMode.active.value)
    }

    @Test
    fun `a hub that refuses the car-mode mark leaves the room again`() {
        hub.succeed = false

        assertEquals(CarSangatResult.NoConnection, toggle())

        assertEquals(listOf("create", "mark", "leave"), room.events)
        assertFalse(carMode.active.value)
    }

    @Test
    fun `ending is allowed after the owner switched sangat off`() {
        val c = controller()
        toggle(c)
        sangatOn = false

        assertEquals(CarSangatResult.Ended, toggle(c))
    }

    @Test
    fun `a second tap while the first is working makes no second room and ends nothing`() =
        runBlocking {
            room.gate = CompletableDeferred()
            val c = controller()
            val first = async(Dispatchers.Default) { c.toggle() }
            while (room.created.isEmpty()) Thread.sleep(5)
            val second = async(Dispatchers.Default) { c.toggle() }
            Thread.sleep(50)
            room.gate!!.complete(Unit)

            assertEquals(CarSangatResult.Started, withTimeout(5_000) { first.await() })
            assertEquals(CarSangatResult.Started, withTimeout(5_000) { second.await() })
            assertEquals(1, room.created.size)
            assertFalse("leave" in room.events)
        }

    @Test
    fun `a re-delivered row selection inside the window is ignored, a later one acts`() {
        var now = 1_000L
        val guard = RepeatSelectionGuard(windowMs = 3_000L) { now }
        assertTrue(guard.shouldAct())
        now += 2_999
        assertFalse("the same selection delivered again must not toggle back", guard.shouldAct())
        now += 3_000
        assertTrue("a real second tap, well after the first", guard.shouldAct())
    }

    @Test
    fun `the first selection always acts, even at clock zero`() {
        assertTrue(RepeatSelectionGuard(windowMs = 3_000L) { 0L }.shouldAct())
    }
}

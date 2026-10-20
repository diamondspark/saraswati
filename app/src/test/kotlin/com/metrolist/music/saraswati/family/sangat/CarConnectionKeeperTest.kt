/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 fix wave — a car-hosted Sangat keeps syncing after the app screen binds and unbinds, and the
// service-made connection never leaks.
package com.metrolist.music.saraswati.family.sangat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CarConnectionKeeperTest {
    private class Conn(val name: String)

    private val disposed = mutableListOf<String>()
    private var made = 0
    private val keeper =
        CarConnectionKeeper<Conn> { disposed += it.name }.also { k -> k.register { Conn("car${++made}") } }
    private val app = Conn("app")

    /** One step of what the manager does when the screen binds or unbinds. */
    private fun bind(
        screen: Conn?,
        hosting: Boolean,
    ): Conn? = keeper.resolve(screen, hosting).also { keeper.settle(it) }

    @Test fun `unbinding the screen while hosting hands the room to a car connection that keeps syncing`() {
        assertSame(app, bind(app, hosting = true))
        val held = bind(null, hosting = true)
        assertEquals("car1", held?.name)
        assertTrue(keeper.isCar(held))
        assertTrue("nothing leaked yet", disposed.isEmpty())
    }

    @Test fun `binding the screen again disposes the car connection`() {
        val car = bind(null, hosting = true)
        assertSame(app, bind(app, hosting = true))
        assertEquals(listOf("car1"), disposed)
        assertFalse(keeper.isCar(car))
    }

    @Test fun `repeated unbinds reuse one car connection, repeated cycles dispose each one`() {
        repeat(3) {
            assertSame(bind(null, hosting = true), bind(null, hosting = true))
            bind(app, hosting = true)
        }
        assertEquals(listOf("car1", "car2", "car3"), disposed)
        assertEquals(3, made)
    }

    @Test fun `with no room to host an unbound screen leaves no connection and nothing leaks`() {
        assertNull(bind(null, hosting = false))
        assertEquals(0, made)
        bind(null, hosting = true)
        assertNull(bind(null, hosting = false))
        assertEquals(listOf("car1"), disposed)
    }

    @Test fun `nothing is made before the service registers a factory`() {
        val bare = CarConnectionKeeper<Conn> { disposed += it.name }
        assertNull(bare.resolve(null, hosting = true))
    }

    @Test fun `a forgotten factory makes nothing more`() {
        keeper.register(null)
        assertNull(keeper.resolve(null, hosting = true))
    }

    @Test fun `settle never disposes the connection that is held`() {
        val car = keeper.resolve(null, hosting = true)
        keeper.settle(car)
        assertTrue(disposed.isEmpty())
    }

    @Test fun `the manager routes every binding through the keeper`() {
        val text = listOf(File("src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherManager.kt"), File("app/src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherManager.kt")).first { it.isFile }.readText()
        val setter = text.substringAfter("fun setPlayerConnection(connection: PlayerConnection?) {").substringBefore("private fun bindPlayerConnection")
        assertTrue(setter.contains("carConnection.resolve(app = connection, hosting = isInRoom && isHost)"))
        assertTrue(setter.contains("carConnection.settle(effective)"))
        assertTrue(text.contains("fun releaseCarConnection("))
        assertTrue("a room that ends frees the car connection", text.substringAfter("fun leaveRoom()").substringBefore("fun approveJoin").contains("releaseCarConnection()"))
        val service = listOf(File("src/main/kotlin/com/metrolist/music/playback/MusicService.kt"), File("app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt")).first { it.isFile }.readText()
        assertTrue(service.substringAfter("override fun onDestroy()").contains("listenTogetherManager.releaseCarConnection(serviceGone = true)"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TickWhilePresentTest {
    @Test fun `nothing ticks while the source is null`() =
        runBlocking {
            val source = MutableStateFlow<String?>(null)
            val seen = mutableListOf<String?>()
            val job = launch { tickWhilePresent(source, 10L).collect { seen += it } }
            delay(120)
            assertEquals("one null to reset the credit, then silence", listOf<String?>(null), seen.toList())
            job.cancel()
        }

    @Test fun `ticks while present, stops when the source goes null`() =
        runBlocking {
            val source = MutableStateFlow<String?>("room")
            val seen = mutableListOf<String?>()
            val job = launch { tickWhilePresent(source, 10L).collect { seen += it } }
            delay(120)
            assertTrue("it re-emitted while present: ${seen.size}", seen.size >= 4)
            assertTrue(seen.all { it == "room" })
            source.value = null
            delay(60)
            val settled = seen.size
            delay(120)
            assertEquals("no ticks after the source went null", settled, seen.size)
            assertNull(seen.last())
            job.cancel()
        }

    @Test fun `a new value replaces the ticking one at once`() =
        runBlocking {
            val source = MutableStateFlow<String?>("a")
            val seen = mutableListOf<String?>()
            val job = launch { tickWhilePresent(source, 1_000L).collect { seen += it } }
            delay(50)
            source.value = "b"
            delay(50)
            assertEquals(listOf<String?>("a", "b"), seen.toList())
            job.cancel()
        }

    @Test fun `the manager uses the ticker and a cached username, not a per-tick DataStore read`() {
        val text = listOf(File("src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherManager.kt"), File("app/src/main/kotlin/com/metrolist/music/listentogether/ListenTogetherManager.kt")).first { it.isFile }.readText()
        val body = text.substringAfter("private fun observePassengerCredit()").substringBefore("private fun registerPassengerPlay")
        assertTrue(body.contains("tickWhilePresent(remoteNowPlaying, PASSENGER_CREDIT_TICK_MS)"))
        assertFalse(body.contains("data.first()"))
        assertTrue(body.contains("username.value"))
    }
}

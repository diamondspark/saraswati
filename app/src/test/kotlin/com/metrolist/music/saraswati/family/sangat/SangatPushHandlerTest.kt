/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Sangat push — what the phone does on the hub's "a Sangat changed" message, against fakes for the hub
// list and the notification shade.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.LiveSangatRoom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SangatPushHandlerTest {
    private class FakeShade : SangatNotifier {
        val shown = linkedSetOf<String>()
        val posts = mutableListOf<String>()
        val cancels = mutableListOf<String>()

        override fun postedRoomCodes(): Set<String> = shown.toSet()

        override fun post(room: LiveSangatRoom) {
            posts += room.roomCode
            shown += room.roomCode
        }

        override fun cancel(roomCode: String) {
            cancels += roomCode
            shown -= roomCode
        }
    }

    private val shade = FakeShade()
    private var live: List<LiveSangatRoom>? = emptyList()
    private var self: String? = "me"
    private var here: String? = null
    private var dismissed: Set<String> = emptySet()
    private var saral = false
    private val announced = HashSet<String>()

    private fun handler() =
        SangatPushHandler(
            fetchLive = { live },
            claimUnannounced = { rooms -> rooms.filter { announced.add(it.roomCode) } },
            selfDeviceId = { self },
            currentRoomCode = { here },
            dismissed = { dismissed },
            isSaral = { saral },
            notifier = shade,
        )

    private fun room(
        code: String,
        host: String = "other",
    ) = LiveSangatRoom(code, "Asha", host, false, 1, "2026-10-25T10:00:00.000Z")

    @Test
    fun `a new family room is posted`() =
        runBlocking {
            live = listOf(room("AAAA"), room("BBBB"))
            handler().onSangatChanged()
            assertEquals(listOf("AAAA", "BBBB"), shade.posts)
        }

    @Test
    fun `a room this phone hosts is not posted`() =
        runBlocking {
            live = listOf(room("AAAA", host = "me"), room("BBBB"))
            handler().onSangatChanged()
            assertEquals(listOf("BBBB"), shade.posts)
        }

    @Test
    fun `a dismissed room is not posted, and neither is the room this phone is in`() =
        runBlocking {
            live = listOf(room("AAAA"), room("BBBB"), room("CCCC"))
            dismissed = setOf("AAAA")
            here = "BBBB"
            handler().onSangatChanged()
            assertEquals(listOf("CCCC"), shade.posts)
        }

    @Test
    fun `a room that has ended is cancelled and a live one is kept`() =
        runBlocking {
            shade.shown += listOf("AAAA", "BBBB")
            announced += listOf("AAAA", "BBBB")
            live = listOf(room("BBBB"))
            handler().onSangatChanged()
            assertEquals(listOf("AAAA"), shade.cancels)
            assertEquals(setOf("BBBB"), shade.shown)
            assertTrue(shade.posts.isEmpty())
        }

    @Test
    fun `all rooms ending cancels every posted notification`() =
        runBlocking {
            shade.shown += listOf("AAAA", "BBBB")
            live = emptyList()
            handler().onSangatChanged()
            assertEquals(setOf("AAAA", "BBBB"), shade.cancels.toSet())
            assertTrue(shade.shown.isEmpty())
        }

    @Test
    fun `a failed fetch changes nothing`() =
        runBlocking {
            shade.shown += "AAAA"
            live = null
            handler().onSangatChanged()
            assertTrue(shade.posts.isEmpty())
            assertTrue(shade.cancels.isEmpty())
            assertEquals(setOf("AAAA"), shade.shown)
        }

    @Test
    fun `a second message for the same room does not post it again`() =
        runBlocking {
            live = listOf(room("AAAA"))
            val handler = handler()
            handler.onSangatChanged()
            handler.onSangatChanged()
            assertEquals(listOf("AAAA"), shade.posts)
        }

    @Test
    fun `a room the foreground poll already announced is not posted by the push`() =
        runBlocking {
            announced += "AAAA"
            live = listOf(room("AAAA"), room("BBBB"))
            handler().onSangatChanged()
            assertEquals(listOf("BBBB"), shade.posts)
        }

    @Test
    fun `a room already in the shade is not posted again even if the process is new`() =
        runBlocking {
            shade.shown += "AAAA"
            live = listOf(room("AAAA"))
            handler().onSangatChanged()
            assertTrue(shade.posts.isEmpty())
            assertTrue(shade.cancels.isEmpty())
        }

    @Test
    fun `a swiped-away room is not posted again when another room starts`() =
        runBlocking {
            live = listOf(room("AAAA"))
            val handler = handler()
            handler.onSangatChanged()
            shade.shown.clear()
            live = listOf(room("AAAA"), room("BBBB"))
            handler.onSangatChanged()
            assertEquals(listOf("AAAA", "BBBB"), shade.posts)
        }

    @Test
    fun `a Saral phone posts and cancels nothing`() =
        runBlocking {
            saral = true
            shade.shown += "OLD"
            live = listOf(room("AAAA"))
            handler().onSangatChanged()
            assertTrue(shade.posts.isEmpty())
            assertTrue(shade.cancels.isEmpty())
        }

    @Test
    fun `with no device id every room is another phone's`() =
        runBlocking {
            self = null
            live = listOf(room("AAAA", host = "me"))
            handler().onSangatChanged()
            assertEquals(listOf("AAAA"), shade.posts)
        }
}

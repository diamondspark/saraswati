/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 10 — a join by code takes its mode from the room on the live list, so a car room joined by
// code is a remote controller and a normal room is not.
package com.metrolist.music.saraswati.family.sangat

import com.metrolist.music.saraswati.family.LiveSangatRoom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinModeTest {
    private fun room(
        code: String,
        car: Boolean,
    ) = LiveSangatRoom(code, "Papa", "host", car, 1, "2026-10-25T10:00:00.000Z")

    @Test
    fun `joining a car room by code is a remote-controller join`() {
        val live = listOf(room("CAR1CAR1", car = true))
        assertTrue(RemoteControllerPolicy.joinsAsRemoteController("CAR1CAR1", live))
    }

    @Test
    fun `joining a normal room by code is not a remote-controller join`() {
        val live = listOf(room("NORM1234", car = false))
        assertFalse(RemoteControllerPolicy.joinsAsRemoteController("NORM1234", live))
    }

    @Test
    fun `the code is matched whatever its case`() {
        assertTrue(RemoteControllerPolicy.joinsAsRemoteController("car1car1", listOf(room("CAR1CAR1", car = true))))
    }

    @Test
    fun `a car room that is not on the live list is joined as a guest, as before`() {
        assertFalse(RemoteControllerPolicy.joinsAsRemoteController("CAR1CAR1", emptyList()))
    }

    @Test
    fun `another room being a car room does not make this one one`() {
        val live = listOf(room("CAR1CAR1", car = true), room("NORM1234", car = false))
        assertFalse(RemoteControllerPolicy.joinsAsRemoteController("NORM1234", live))
    }
}

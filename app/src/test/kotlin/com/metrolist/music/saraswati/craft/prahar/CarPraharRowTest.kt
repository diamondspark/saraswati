/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 7 — the car's Prahar row shows only the current window's mix.
package com.metrolist.music.saraswati.craft.prahar

import android.net.Uri
import com.metrolist.music.saraswati.car.CarResources
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CarPraharRowTest {
    private val resources =
        CarResources(
            string = { if (it == com.metrolist.music.R.string.car_prahar_title) "Prahar · %1\$s" else "Time-of-day mix · %1\$s" },
            iconUri = { Uri.parse("icon://$it") },
        )
    private val day = LocalDate.of(2026, 10, 19)
    private val morning = LocalDateTime.of(day, java.time.LocalTime.of(7, 0))
    private val track = MixTrack("abcdefghijk", "Song", "Artist", null, 1000L)

    private fun mix(prahar: Prahar) = PraharMix(prahar, day, 1L, listOf(track))

    @Test
    fun `the car row is absent with no cached mix`() {
        assertNull(CarPraharRow.item(null, morning, resources))
    }

    @Test
    fun `the car row is absent when the cached mix is from another window`() {
        assertNull(CarPraharRow.item(mix(Prahar.SANDHYA), morning, resources))
    }

    @Test
    fun `the car row is shown for the current window with its title and subtitle`() {
        val item = CarPraharRow.item(mix(Prahar.PRATAH), morning, resources)!!
        assertEquals(CarPraharRow.MEDIA_ID, item.mediaId)
        assertEquals("Prahar · Pratah", item.mediaMetadata.title.toString())
        assertEquals("Time-of-day mix · Morning", item.mediaMetadata.subtitle.toString())
    }
}

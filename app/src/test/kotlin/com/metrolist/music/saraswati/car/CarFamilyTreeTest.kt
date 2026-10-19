/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 15 — the car's Family folder, row by row.
package com.metrolist.music.saraswati.car

import android.net.Uri
import androidx.media3.common.MediaItem
import com.metrolist.music.R
import com.metrolist.music.playback.MusicService
import com.metrolist.music.saraswati.family.FamilyFeature
import com.metrolist.music.saraswati.family.FamilyPlaylist
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.ParivaarMix
import com.metrolist.music.saraswati.family.sangat.CarSangatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CarFamilyTreeTest {
    private val words =
        mapOf(
            R.string.car_family_parivaar_mix to "Parivaar Mix · Family mix",
            R.string.car_family_sangat_start to "Start Sangat",
            R.string.car_family_sangat_end to "End Sangat · %1\$d joined",
        )
    private val resources = CarResources(string = { words[it] ?: "string#$it" }, iconUri = { Uri.parse("icon://$it") })

    private val mix = ParivaarMix(1L, listOf(MixTrack("abcdefghijk", "Song", "Artist", null, 1000L)))
    private val playlists =
        listOf(
            FamilyPlaylist("1", "PLfamily_one-1", "Road songs", "dev1", "2026-10-19T00:00:00Z"),
            FamilyPlaylist("2", "PLfamily_two-2", "Bhajans", "dev2", "2026-10-19T00:00:00Z"),
        )
    private val allOn: (FamilyFeature) -> Boolean = { true }
    private val idle = CarSangatState(hosting = false, joinedCount = 0)

    private fun children(
        mix: ParivaarMix = this.mix,
        playlists: List<FamilyPlaylist> = this.playlists,
        enabled: (FamilyFeature) -> Boolean = allOn,
        sangat: CarSangatState = idle,
    ): List<MediaItem> = CarFamilyTree.children(mix, playlists, enabled, sangat, resources)

    private val MediaItem.title get() = mediaMetadata.title?.toString()

    @Test
    fun `lists Parivaar Mix, then family playlists, then Sangat`() {
        val rows = children()

        assertEquals(
            listOf("parivaar_mix", "${MusicService.YOUTUBE_PLAYLIST}/PLfamily_one-1", "${MusicService.YOUTUBE_PLAYLIST}/PLfamily_two-2", "sangat_toggle"),
            rows.map { it.mediaId },
        )
        assertEquals(listOf("Parivaar Mix · Family mix", "Road songs", "Bhajans", "Start Sangat"), rows.map { it.title })
        assertTrue("every row is something to play", rows.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false })
    }

    @Test
    fun `hides each feature whose switch is off`() {
        fun without(feature: FamilyFeature) = children(enabled = { it != feature }).map { it.mediaId }

        assertEquals(listOf("${MusicService.YOUTUBE_PLAYLIST}/PLfamily_one-1", "${MusicService.YOUTUBE_PLAYLIST}/PLfamily_two-2", "sangat_toggle"), without(FamilyFeature.PARIVAAR_MIX))
        assertEquals(listOf("parivaar_mix", "sangat_toggle"), without(FamilyFeature.FAMILY_PLAYLISTS))
        assertEquals(listOf("parivaar_mix", "${MusicService.YOUTUBE_PLAYLIST}/PLfamily_one-1", "${MusicService.YOUTUBE_PLAYLIST}/PLfamily_two-2"), without(FamilyFeature.SANGAT))
        assertEquals(emptyList<String>(), children(enabled = { false }).map { it.mediaId })
    }

    @Test
    fun `an empty cache gives only the Sangat row`() {
        val rows = children(mix = ParivaarMix.EMPTY, playlists = emptyList())

        assertEquals(listOf("sangat_toggle"), rows.map { it.mediaId })
    }

    @Test
    fun `the Sangat row reads End Sangat · 2 joined while hosting`() {
        val rows = children(sangat = CarSangatState(hosting = true, joinedCount = 2))

        assertEquals("End Sangat · 2 joined", rows.last().title)
        assertEquals("sangat_toggle", rows.last().mediaId)
    }

    @Test
    fun `a playlist id that is not a plain YouTube id gets no row`() {
        val odd = listOf(FamilyPlaylist("3", "a/b", "Odd", "d", "t"), FamilyPlaylist("4", "", "Blank", "d", "t"), playlists[0])

        assertEquals(
            listOf("${MusicService.YOUTUBE_PLAYLIST}/PLfamily_one-1"),
            children(mix = ParivaarMix.EMPTY, playlists = odd, enabled = { it == FamilyFeature.FAMILY_PLAYLISTS }).map { it.mediaId },
        )
    }

    @Test
    fun `the car is told to ask again when Sangat starts and when a joiner arrives, not when nothing changed`() {
        val sangat = MutableStateFlow(idle)
        val told = mutableListOf<Int>()

        runBlocking {
            val job = launch(Dispatchers.Unconfined) { carFamilyChanges(listOf(sangat), { children(sangat = sangat.value) }).collect { told += it } }
            sangat.value = CarSangatState(true, 0)
            sangat.value = CarSangatState(true, 0)
            sangat.value = CarSangatState(true, 1)
            job.cancel()
        }

        // The row count stays 4 throughout; what changes is its title, and each change is announced once.
        assertEquals(listOf(4, 4, 4), told)
    }

    private fun sourceText(path: String): String =
        (listOf(java.io.File(path), java.io.File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")).readText()

    @Test
    fun `the Sangat row hands back the player's own queue and the mix is read from the phone`() {
        val callback = sourceText("src/main/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallback.kt")
        val onSetMediaItems = callback.substringAfter("override fun onSetMediaItems")
        val sangatBranch = onSetMediaItems.substringAfter("CarFamilyTree.SANGAT_TOGGLE_ID ->").substringBefore("MusicService.SEARCH ->")
        val mixBranch = onSetMediaItems.substringAfter("CarFamilyTree.PARIVAAR_MIX_ID ->").substringBefore("CarFamilyTree.SANGAT_TOGGLE_ID ->")

        assertTrue(sangatBranch.contains("currentQueue(mediaSession.player)"))
        assertTrue("the queue is read on the player's thread", sangatBranch.contains("Dispatchers.Main"))
        assertTrue(mixBranch.contains("parivaarMixStore.cached()"))
        assertTrue(callback.contains("MediaSessionConstants.ACTION_TOGGLE_SANGAT -> toggleSangat()"))
    }

    @Test
    fun `the service tells the car when the Family folder changes and shows the button only while Sangat is on`() {
        val service = sourceText("src/main/kotlin/com/metrolist/music/playback/MusicService.kt")

        assertTrue(service.contains("notifyChildrenChanged(MusicService.CAR_FAMILY"))
        assertTrue(service.substringAfter("private fun sangatButton()").substringBefore("return listOf").contains("isEnabled(com.metrolist.music.saraswati.family.FamilyFeature.SANGAT)"))
    }

    @Test
    fun `the family playlists are refreshed on resume beside the stations`() {
        val main = sourceText("src/main/kotlin/com/metrolist/music/MainActivity.kt")

        assertTrue(main.contains("launch { stationsRefresher.refresh() }"))
        assertTrue(main.contains("launch { familyPlaylistsRepository.refresh() }"))
    }
}

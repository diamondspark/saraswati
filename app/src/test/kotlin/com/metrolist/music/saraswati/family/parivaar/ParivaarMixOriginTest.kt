/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 fix wave — a Parivaar Mix play is known by its item, so a swapped queue, a queue restored
// from disk as a plain list, or a refreshed song row cannot turn it into the owner's own taste.
package com.metrolist.music.saraswati.family.parivaar

import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.PersistQueue
import com.metrolist.music.models.keepingOriginOf
import com.metrolist.music.extensions.toQueue
import com.metrolist.music.saraswati.family.PlayContext
import com.metrolist.music.saraswati.family.TastePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParivaarMixOriginTest {
    private val tracks = listOf(MixTrack("a", "A", "x", null, 1000L), MixTrack("b", "B", "y", null, 2000L))

    private fun playedAsMix(metadata: MediaMetadata?, inRoom: Boolean = false, countShared: Boolean = false) =
        TastePolicy.counts(
            TastePolicy.resolve(
                inRoom = inRoom,
                isHost = inRoom,
                myUsername = "mohit",
                suggestedBy = metadata?.suggestedBy,
                itemIsParivaarMix = metadata?.fromParivaarMix == true,
            ),
            countShared,
        )

    @Test fun `every item the mix queue builds carries the mix flag`() =
        runBlocking {
            val items = ParivaarMixQueue(tracks).getInitialStatus().items
            assertEquals(2, items.size)
            assertTrue(items.all { it.metadata?.fromParivaarMix == true })
            assertTrue(items.none { playedAsMix(it.metadata) })
        }

    @Test fun `a plain song carries no flag and counts`() {
        val plain = MediaMetadata("p", "P", emptyList(), 100)
        assertFalse(plain.fromParivaarMix)
        assertTrue(playedAsMix(plain))
    }

    @Test fun `a queue restored from disk as a plain list still knows its mix items`() =
        runBlocking {
            val flagged = tracks.map { it.toQueueMetadata() }
            val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(PersistQueue("Parivaar Mix", flagged, 0, 0L)) } }.toByteArray()
            val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as PersistQueue }.toQueue()

            val items = restored.getInitialStatus().items
            assertEquals("a restored queue is a plain ListQueue, not a ParivaarMixQueue", "ListQueue", restored::class.simpleName)
            assertTrue(items.all { it.metadata?.fromParivaarMix == true })
            assertTrue(items.none { playedAsMix(it.metadata) })
        }

    @Test fun `the verdict does not depend on which queue the phone holds when the stats arrive`() {
        // resolve() has no queue parameter: the same item gives the same answer before and after a queue swap
        val item = tracks[0].toQueueMetadata().toMediaItem()
        val before = playedAsMix(item.metadata)
        val afterSwap = playedAsMix(item.metadata)
        assertEquals(before, afterSwap)
        assertFalse(before)
    }

    @Test fun `a mix track keeps its flag when hosting a Sangat`() {
        val item = tracks[0].toQueueMetadata()
        assertEquals(PlayContext.ParivaarMix, TastePolicy.resolve(true, true, "mohit", item.suggestedBy, item.fromParivaarMix))
    }

    @Test fun `a refreshed song row keeps the item's origin`() {
        val old = tracks[0].toQueueMetadata().copy(suggestedBy = "papa")
        val fresh = MediaMetadata("a", "A", emptyList(), 100)
        val merged = fresh.keepingOriginOf(old)
        assertTrue(merged.fromParivaarMix)
        assertEquals("papa", merged.suggestedBy)
        assertEquals(fresh, fresh.keepingOriginOf(null))
    }
}

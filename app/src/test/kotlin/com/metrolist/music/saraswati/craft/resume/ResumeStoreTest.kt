/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.saraswati.craft.resume

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumeStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private var now = 1_000_000L

    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store() = ResumeStore(dataStore, { now }, scope)

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun point(
        sourceId: String,
        index: Int = 2,
        positionMs: Long = 60_000L,
        savedAt: Long = now,
    ) = ResumePoint(sourceId, "vid-$sourceId-$index", index, positionMs, savedAt)

    @Test
    fun `the 28th collection evicts the least recently saved`() {
        val store = store()
        blocking { store.awaitHydrated() }
        repeat(ResumeStore.RESUME_MAX) { i ->
            now += 1_000
            blocking { store.save(point("c%02d".format(i), savedAt = now)) }
        }
        assertEquals(ResumeStore.RESUME_MAX, blocking { store.points.first().size })

        now += 1_000
        blocking { store.save(point("c-new", savedAt = now)) }

        val kept = blocking { store.points.first() }
        assertEquals(ResumeStore.RESUME_MAX, kept.size)
        assertNull("the least recently saved one is gone", kept["c00"])
        assertTrue(kept.containsKey("c-new"))
    }

    @Test
    fun `a clear removes the point for that collection only`() {
        val store = store()
        blocking { store.save(point("a")) }
        blocking { store.save(point("b")) }
        blocking { store.clear("a") }

        assertNull(store.get("a"))
        assertTrue(store.get("b") != null)
    }

    @Test
    fun `under 30 seconds into the first song offers no resume`() {
        assertFalse(ResumePolicy.offer(point("a", index = 0, positionMs = 29_999L), now))
        assertTrue(ResumePolicy.offer(point("a", index = 0, positionMs = 30_000L), now))
        assertTrue("any later song is offered", ResumePolicy.offer(point("a", index = 1, positionMs = 0L), now))
    }

    @Test
    fun `points older than 30 days are not offered`() {
        val saved = point("a", savedAt = now)
        assertTrue(ResumePolicy.offer(saved, now + 30 * day - 1))
        assertFalse(ResumePolicy.offer(saved, now + 30 * day))
    }

    @Test
    fun `corrupt stored data reads as no points`() {
        blocking {
            dataStore.edit { it[stringPreferencesKey("sarCraftResumePoints")] = "[oops" }
        }
        val store = store()
        blocking { store.awaitHydrated() }
        assertNull(store.get("a"))
        assertTrue(blocking { store.points.first() }.isEmpty())
    }
}

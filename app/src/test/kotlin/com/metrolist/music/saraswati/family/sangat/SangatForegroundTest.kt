/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 review fix (M13) — the foreground count is the process's, not an Activity's.
package com.metrolist.music.saraswati.family.sangat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.music.saraswati.family.FakeHub
import com.metrolist.music.saraswati.family.FamilyFeatureGate
import com.metrolist.music.saraswati.family.HubIdentity
import com.metrolist.music.saraswati.family.LiveSangatRoom
import com.metrolist.music.saraswati.family.VirtualTimeDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SangatForegroundTest {
    @get:Rule val folder = TemporaryFolder()

    private val polls = mutableListOf<String>()
    private val hub =
        object : SangatHubApi {
            override suspend fun getLiveSangat(sessionToken: String): Result<List<LiveSangatRoom>> {
                polls += sessionToken
                return Result.success(emptyList())
            }

            override suspend fun setSangatCarMode(
                sessionToken: String,
                roomCode: String,
                carMode: Boolean,
            ) = Result.success(Unit)
        }
    private val identity =
        object : HubIdentity {
            override suspend fun sessionToken() = "sess"

            override suspend fun deviceId() = "me"
        }
    private lateinit var time: VirtualTimeDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var ioScope: CoroutineScope
    private lateinit var watcher: SangatLiveWatcher
    private lateinit var foreground: SangatForeground

    @Before
    fun setUp() {
        time = VirtualTimeDispatcher()
        scope = CoroutineScope(SupervisorJob() + time)
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = ioScope) { folder.newFile("settings.preferences_pb") }
        watcher = SangatLiveWatcher(hub, identity, FamilyFeatureGate(FakeHub(), identity, dataStore, ioScope)) { time.currentTime }
        foreground = SangatForeground(watcher, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        ioScope.cancel()
    }

    @Test
    fun `a second activity's start before the first one's stop keeps the poll running`() {
        foreground.activityStarted()
        time.runCurrent()
        foreground.activityStarted()
        time.runCurrent()
        assertEquals("a second start does not restart the poll", 1, polls.size)

        foreground.activityStopped()
        assertTrue(watcher.running)

        foreground.activityStopped()
        assertFalse(watcher.running)
    }

    @Test
    fun `a saral phone never polls, and leaving saral while on screen starts it`() {
        foreground.setSaral(true)
        foreground.activityStarted()
        time.runCurrent()
        assertEquals(0, polls.size)
        assertFalse(watcher.running)

        foreground.setSaral(false)
        time.runCurrent()
        assertEquals(1, polls.size)
        assertTrue(watcher.running)
    }

    @Test
    fun `a stop without a start is harmless`() {
        foreground.activityStopped()
        foreground.activityStarted()
        time.runCurrent()
        assertTrue(watcher.running)
    }
}

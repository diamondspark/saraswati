/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 7 — the Prahar refresh against a fake database and a fake YouTube radio, with a real
// file-backed DataStore.
package com.metrolist.music.saraswati.craft.prahar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.family.parivaar.PlaylistEntry
import com.metrolist.music.saraswati.family.parivaar.YouTubePlaylistPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class PraharRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var store: PraharStore

    private val radioCalls = mutableListOf<String>()
    private var radioFails = false

    private val morningPlays = (1..6).map { PraharEventRow("s$it", LocalDateTime.parse("2026-10-18T07:00"), 1_000L) }
    private var plays: List<PraharEventRow> = morningPlays

    private val dao =
        object : PraharDao {
            override suspend fun praharPlays(fromTimeStamp: LocalDateTime) = plays

            override suspend fun songsByIds(ids: List<String>): List<Song> =
                ids.map { id ->
                    Song(
                        song = SongEntity(id = id, title = "title $id", duration = 200),
                        artists = listOf(ArtistEntity(id = "a", name = "artist")),
                    )
                }
        }

    private val port =
        object : YouTubePlaylistPort {
            override suspend fun createUnlisted(title: String): Result<String> = error("unused")

            override suspend fun entries(playlistId: String): Result<List<PlaylistEntry>> = error("unused")

            override suspend fun add(
                playlistId: String,
                videoId: String,
            ): Result<Unit> = error("unused")

            override suspend fun remove(
                playlistId: String,
                videoId: String,
                setVideoId: String,
            ): Result<Unit> = error("unused")

            override suspend fun delete(playlistId: String): Result<Unit> = error("unused")

            override suspend fun radio(seedVideoId: String): Result<List<MixTrack>> {
                radioCalls += seedVideoId
                if (radioFails) return Result.failure(java.io.IOException("offline"))
                return Result.success((1..20).map { MixTrack("r$it", "radio $it", "artist", null, null) })
            }
        }

    private fun repository() = PraharRepository(dao, port, store, nowMillis = { 42L })

    // Plays in the 07:00 window and again in the 10:00 window, so each window has own songs to seed from.
    private val tenOClockPlays = (1..6).map { PraharEventRow("s$it", LocalDateTime.parse("2026-10-18T10:00"), 1_000L) }

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create { folder.newFile("prahar.preferences_pb") }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PraharStore(dataStore, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val morning = LocalDateTime.parse("2026-10-19T07:00")

    @Test
    fun `refresh builds once per window and day`() =
        runBlocking {
            repository().refreshIfDue(morning).getOrThrow()
            repository().refreshIfDue(morning.plusMinutes(30)).getOrThrow()
            assertEquals(1, radioCalls.size)
            assertEquals(PraharMixBuilder.MIX_MAX, store.cached()!!.tracks.size)
            assertEquals(Prahar.PRATAH, store.cached()!!.prahar)
        }

    @Test
    fun `refresh makes at most one radio call per window`() =
        runBlocking {
            plays = morningPlays + tenOClockPlays
            repository().refreshIfDue(morning).getOrThrow()
            repository().refreshIfDue(morning.plusHours(1)).getOrThrow()
            repository().refreshIfDue(morning.plusHours(3)).getOrThrow()
            assertEquals(2, radioCalls.size)
        }

    @Test
    fun `a radio failure still saves a mix of own songs`() =
        runBlocking {
            radioFails = true
            repository().refreshIfDue(morning).getOrThrow()
            val mix = store.cached()
            assertTrue(mix != null && mix.tracks.isNotEmpty())
            assertTrue(mix!!.tracks.all { it.videoId.startsWith("s") })
        }

    @Test
    fun `fewer than 5 own songs saves no mix`() =
        runBlocking {
            plays = morningPlays.take(4)
            repository().refreshIfDue(morning).getOrThrow()
            assertNull(store.cached())
            assertEquals(0, radioCalls.size)
        }

    @Test
    fun `local songs are never sent to radio`() =
        runBlocking {
            plays = morningPlays + PraharEventRow("local:file", LocalDateTime.parse("2026-10-18T07:00"), 1L)
            repository().refreshIfDue(morning).getOrThrow()
            assertTrue(radioCalls.none { it.startsWith("local:") })
            assertTrue(store.cached()!!.tracks.none { it.videoId.startsWith("local:") })
        }
}

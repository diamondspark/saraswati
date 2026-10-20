package com.metrolist.music.saraswati.localmedia

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.saraswati.LocalSongEgressException
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.saraswati.family.PlaybackStatsPlan
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.utils.NetworkConnectivityObserver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalEgressTest {
    private lateinit var context: Context
    private lateinit var internal: InternalDatabase
    private lateinit var database: MusicDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        internal =
            Room
                .inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        database = MusicDatabase(internal)
    }

    @After
    fun tearDown() {
        internal.close()
    }

    private fun metadata(id: String) = MediaMetadata(id = id, title = "Song $id", artists = emptyList(), duration = 120)

    @Test
    fun `playerResponseForPlayback fails for a local id without any request`() =
        runBlocking {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // The player is never initialised in this test: any attempt to resolve would fail differently.
            val result =
                InnerTubeXPlayer.playerResponseForPlayback(
                    videoId = "local:42",
                    audioQuality = AudioQuality.AUTO,
                    connectivityManager = connectivity,
                )
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is LocalSongEgressException)
        }

    @Test
    fun `getLyrics returns not-found for a local id without calling a provider`() =
        runBlocking {
            val helper = LyricsHelper(context, NetworkConnectivityObserver(context))
            val started = System.nanoTime()
            val result = helper.getLyrics(metadata("local:42"))
            assertEquals(LYRICS_NOT_FOUND, result.lyrics)
            assertEquals("", result.provider)
            // A provider lookup waits on timeouts of seconds; the guard answers at once.
            assertTrue((System.nanoTime() - started) < 2_000_000_000L)
        }

    @Test
    fun `download of a local id enqueues nothing`() = runBlocking {
        val provider = StandaloneDatabaseProvider(context)
        val cacheDir = File(context.cacheDir, "egress-test-cache").apply { mkdirs() }
        val cache = SimpleCache(cacheDir, NoOpCacheEvictor(), provider)
        val util = DownloadUtil(context, database, provider, cache, cache)
        try {
            util.download(metadata("local:42"))
            util.download("local:43")
            util.download(
                com.metrolist.music.db.entities.Song(SongEntity(id = "local:44", title = "x", duration = 1), artists = emptyList()),
            )
            // Control: an ordinary id is accepted, so the absence of a local row below is the guard's doing.
            util.download(metadata("dQw4w9WgXcQ"))
            val deadline = System.currentTimeMillis() + 10_000
            while (database.songEntity("dQw4w9WgXcQ") == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(50)
            }
            assertTrue(database.songEntity("dQw4w9WgXcQ") != null)
            assertNull(database.songEntity("local:42"))
            assertNull(database.songEntity("local:43"))
            assertNull(database.songEntity("local:44"))
            assertTrue(util.downloadManager.downloadIndex.getDownload("local:42") == null)
            val app = ApplicationProvider.getApplicationContext<Application>()
            var intent = shadowOf(app).nextStartedService
            while (intent != null) {
                assertFalse(intent.dataString.orEmpty().contains("local:"))
                intent = shadowOf(app).nextStartedService
            }
        } finally {
            util.release()
            cache.release()
        }
    }

    @Test
    fun `a local play writes its Event row and never calls registerPlayback`() =
        runBlocking {
            database.insert(SongEntity(id = "local:42", title = "Local", duration = 200, isLocal = true))
            val plan =
                PlaybackStatsPlan.of(
                    totalPlayTimeMs = 60_000,
                    historyDurationMs = 30_000,
                    historyPaused = false,
                    counts = true,
                    mediaId = "local:42",
                )
            assertTrue(plan.writeLocalEvent)
            assertFalse(plan.registerWithYouTube)
            if (plan.writeLocalEvent) {
                database.insert(Event(songId = "local:42", timestamp = LocalDateTime.now(), playTime = 60_000))
            }
            internal.query("SELECT COUNT(*) FROM event WHERE songId = 'local:42'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            val video = PlaybackStatsPlan.of(60_000, 30_000, false, true, mediaId = "dQw4w9WgXcQ")
            assertTrue(video.registerWithYouTube)
        }
}

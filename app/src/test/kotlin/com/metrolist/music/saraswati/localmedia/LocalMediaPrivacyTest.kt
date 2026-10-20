package com.metrolist.music.saraswati.localmedia

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What songs on this phone may touch: the MediaStore audio table, and nothing that logs or sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalMediaPrivacyTest {
    class RecordingProvider : ContentProvider() {
        val queried = mutableListOf<Triple<Uri, List<String>, String?>>()

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            queried += Triple(uri, projection.orEmpty().toList(), selection)
            return MatrixCursor(projection ?: emptyArray())
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Test
    fun `the scanner reads only the MediaStore audio table`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val provider = Robolectric.setupContentProvider(RecordingProvider::class.java, MediaStore.AUTHORITY)
            val rows = ContentResolverAudioReader(context.contentResolver, sdkInt = 33).rows()
            assertTrue(rows.isEmpty())
            assertEquals(1, provider.queried.size)
            val (uri, projection, selection) = provider.queried.single()
            assertEquals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, uri)
            assertTrue(selection.orEmpty().contains(MediaStore.Audio.Media.IS_MUSIC))
            assertTrue(projection.none { it.contains("image", true) || it.contains("video", true) })
            // never the file's data path
            assertTrue(MediaStore.Audio.Media.DATA !in projection)
        }

    private fun sources(): List<Pair<String, String>> {
        val dirs =
            listOf(
                "src/main/kotlin/com/metrolist/music/saraswati/localmedia",
                "app/src/main/kotlin/com/metrolist/music/saraswati/localmedia",
            )
        val dir = dirs.map(::File).first { it.isDirectory }
        return dir.listFiles { f -> f.extension == "kt" }!!.map { it.name to it.readText() }
    }

    @Test
    fun `the localmedia package has no network client, no YouTube call and no logging`() {
        val banned =
            listOf(
                "import okhttp3", "import io.ktor", "import java.net", "import com.metrolist.innertube.YouTube",
                "HttpURLConnection", "OkHttp", "Timber", "android.util.Log", "println(", "Log.",
            )
        val files = sources()
        assertTrue(files.isNotEmpty())
        for ((name, text) in files) {
            // The Compose settings file may use Material imports only; it is covered by the same list.
            for (word in banned) {
                assertTrue("$name must not contain $word", !text.contains(word))
            }
        }
    }

    @Test
    fun `the scanner never asks MediaStore for images, video or file paths`() {
        val reader = sources().first { it.first == "MediaStoreAudioReader.kt" }.second
        assertTrue(!reader.contains("Images"))
        assertTrue(!reader.contains("Video"))
        assertTrue(!reader.contains("Media.DATA"))
        assertTrue(!reader.contains("Files.getContentUri"))
    }
}

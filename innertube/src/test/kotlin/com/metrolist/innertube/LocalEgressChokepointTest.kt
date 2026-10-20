package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.saraswati.LocalSongEgressException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real client InnerTube builds for itself (not a test double) must refuse every call that names a song on this
 * phone, before a socket opens. If the guard were missing, these calls would reach for the network and fail with a
 * different error, so each test fails closed.
 */
class LocalEgressChokepointTest {
    private val innerTube = InnerTube()
    private val client = YouTubeClient.WEB_REMIX

    private fun refused(block: suspend () -> Unit) {
        val error = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertTrue("expected the egress guard to refuse, got $error", generateSequence(error) { it.cause }.any { it is LocalSongEgressException })
    }

    @Test fun `next for a local id is refused`() = refused { innerTube.next(client, "local:42", null, null, null, null) }

    @Test fun `player for a local id is refused`() = refused { innerTube.player(client, "local:42", null, null) }

    @Test fun `like and unlike a local id are refused`() {
        refused { innerTube.likeVideo(client, "local:42") }
        refused { innerTube.unlikeVideo(client, "local:42") }
    }

    @Test fun `adding a local id to a playlist is refused`() = refused { innerTube.addToPlaylist(client, "PLabc", "local:42") }

    @Test fun `queue for a local id is refused`() = refused { innerTube.getQueue(client, listOf("local:42"), null) }

    @Test fun `registering a playback url that names a local id is refused`() =
        refused { innerTube.registerPlayback("https://s.youtube.com/api/stats/playback?docid=local:42", "cpn", null) }

    @Test fun `media info for a local id is refused`() {
        val error = runBlocking { innerTube.getMediaInfo("local:42") }.exceptionOrNull()
        assertTrue("got $error", generateSequence(error) { it.cause }.any { it is LocalSongEgressException })
    }

    @Test fun `transcript for a local id is refused`() = refused { innerTube.getTranscript(YouTubeClient.WEB, "local:42") }
}

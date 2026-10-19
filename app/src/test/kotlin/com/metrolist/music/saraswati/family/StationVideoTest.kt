/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 8 — the station editor's "YouTube video or link" field is
// resolved to the video id a station is seeded with. Only a YouTube video
// resolves; anything else (another host, a playlist, a channel, a wrong-length
// id) gives null so the editor can say so instead of saving a station that
// would not play.
package com.metrolist.music.saraswati.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationVideoTest {
    private val id = "dQw4w9WgXcQ"

    @Test
    fun `a bare video id resolves to itself`() {
        assertEquals(id, StationVideo.videoIdFrom(id))
        assertEquals(id, StationVideo.videoIdFrom("  $id \n"))
        assertEquals("a-b_c1234Zz", StationVideo.videoIdFrom("a-b_c1234Zz"))
    }

    @Test
    fun `watch links on every youtube host resolve`() {
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&t=10s",
            "https://m.youtube.com/watch?feature=share&v=$id",
            "https://music.youtube.com/watch?v=$id&list=RDAMVM$id",
            "http://www.youtube.com/watch?v=$id",
            "HTTPS://WWW.YOUTUBE.COM/watch?v=$id",
        ).forEach { assertEquals(it, id, StationVideo.videoIdFrom(it)) }
    }

    @Test
    fun `short, embed, shorts and live links resolve`() {
        listOf(
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=abc123",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube.com/v/$id",
        ).forEach { assertEquals(it, id, StationVideo.videoIdFrom(it)) }
    }

    @Test
    fun `a link inside shared text resolves`() {
        assertEquals(id, StationVideo.videoIdFrom("Listen to this: https://youtu.be/$id Thanks!"))
    }

    @Test
    fun `other hosts and lookalikes do not resolve`() {
        listOf(
            "https://evil.example.com/watch?v=$id",
            "https://www.youtube.com.evil.com/watch?v=$id",
            "https://notyoutube.com/watch?v=$id",
            "https://youtube.com@evil.com/watch?v=$id",
            "https://evil.com/youtu.be/$id",
            "ftp://www.youtube.com/watch?v=$id",
        ).forEach { assertNull(it, StationVideo.videoIdFrom(it)) }
    }

    @Test
    fun `playlists, channels and pages without a video do not resolve`() {
        listOf(
            "https://www.youtube.com/playlist?list=PLabcdefghijklmnop",
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            "https://www.youtube.com/@somechannel",
            "https://www.youtube.com/",
            "https://www.youtube.com/watch",
            "https://youtu.be/",
        ).forEach { assertNull(it, StationVideo.videoIdFrom(it)) }
    }

    @Test
    fun `an id of the wrong length or alphabet does not resolve`() {
        listOf(
            "",
            "   ",
            "short",
            "dQw4w9WgXc",
            "dQw4w9WgXcQQ",
            "dQw4w9WgXc!",
            "https://www.youtube.com/watch?v=dQw4w9WgXc",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXc%20",
            "https://youtu.be/dQw4w9WgXc.",
        ).forEach { assertNull(it, StationVideo.videoIdFrom(it)) }
    }
}

package com.metrolist.music.saraswati.localmedia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibraryFilterTest {
    private fun row(
        path: String? = "Music/",
        durationMs: Long = 200_000,
        recording: Boolean = false,
    ) = MediaStoreAudioRow(
        id = 1,
        title = "t",
        artist = "a",
        album = "b",
        albumId = 2,
        durationMs = durationMs,
        mimeType = "audio/mpeg",
        relativePath = path,
        displayName = "t.mp3",
        dateModifiedSec = 10,
        isRecording = recording,
    )

    @Test
    fun `drops files shorter than 30 seconds`() {
        assertFalse(LocalLibraryFilter.accept(row(durationMs = 29_999)))
        assertTrue(LocalLibraryFilter.accept(row(durationMs = 30_000)))
    }

    @Test
    fun `drops anything under Android such as WhatsApp and Telegram media`() {
        assertFalse(LocalLibraryFilter.accept(row(path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/")))
        assertFalse(LocalLibraryFilter.accept(row(path = "android/data/org.telegram.messenger/files/")))
    }

    @Test
    fun `drops Recordings, Call, call_rec, Voice Recorder, Ringtones, Notifications and Alarms folders in any case`() {
        listOf(
            "Recordings/",
            "Music/RECORDINGS/",
            "Call/",
            "calls/",
            "Recordings/Call/",
            "call_rec/",
            "Call Recordings/",
            "Sound_Recorder/",
            "Voice Recorder/",
            "Voice Notes/",
            "Ringtones/",
            "RINGTONES/",
            "Notifications/",
            "Alarms/",
        ).forEach { assertFalse(it, LocalLibraryFilter.accept(row(path = it))) }
    }

    @Test
    fun `drops IS_RECORDING rows`() {
        assertFalse(LocalLibraryFilter.accept(row(recording = true)))
    }

    @Test
    fun `keeps mp3s under Music and Download`() {
        assertTrue(LocalLibraryFilter.accept(row(path = "Music/")))
        assertTrue(LocalLibraryFilter.accept(row(path = "Download/")))
        assertTrue(LocalLibraryFilter.accept(row(path = "Music/Bhajans/Vol 1/")))
        assertTrue(LocalLibraryFilter.accept(row(path = null)))
    }

    @Test
    fun `keeps a folder named Recall Songs`() {
        assertTrue(LocalLibraryFilter.accept(row(path = "Music/Recall Songs/")))
        assertTrue(LocalLibraryFilter.accept(row(path = "Music/Androids/")))
    }
}

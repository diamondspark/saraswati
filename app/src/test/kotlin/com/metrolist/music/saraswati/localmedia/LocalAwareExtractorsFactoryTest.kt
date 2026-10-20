package com.metrolist.music.saraswati.localmedia

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalAwareExtractorsFactoryTest {
    private val remote =
        ExtractorsFactory { arrayOf(MatroskaExtractor(), FragmentedMp4Extractor(), Mp4Extractor()) }
    private val factory = LocalAwareExtractorsFactory(remote)

    @Test
    fun `content and local schemes get extractors that include mp3, flac, ogg, wav and adts`() {
        for (uri in listOf("content://media/external/audio/media/42", "local:42")) {
            val kinds = factory.createExtractors(Uri.parse(uri), emptyMap()).map { it::class.java }
            for (needed in listOf(Mp3Extractor::class.java, FlacExtractor::class.java, OggExtractor::class.java, WavExtractor::class.java, AdtsExtractor::class.java)) {
                assertTrue("$uri lacks ${needed.simpleName}", needed in kinds)
            }
        }
    }

    @Test
    fun `https keeps exactly Matroska, FragmentedMp4 and Mp4`() {
        val kinds = factory.createExtractors(Uri.parse("https://example.com/audio"), emptyMap()).map { it::class.java }
        assertEquals(listOf(MatroskaExtractor::class.java, FragmentedMp4Extractor::class.java, Mp4Extractor::class.java), kinds)
        assertEquals(3, factory.createExtractors().size)
    }
}

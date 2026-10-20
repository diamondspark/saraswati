/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 4 — YouTube streams are only ever Matroska/WebM or MP4, so the player keeps just those
// extractors. Files on this phone can be anything the phone plays (mp3, flac, ogg, wav, aac), so for the `content`
// and `local` schemes the full default set is used. Everything else gets exactly the old set.
package com.metrolist.music.saraswati.localmedia

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

@UnstableApi
class LocalAwareExtractorsFactory(
    private val remote: ExtractorsFactory,
    private val local: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = remote.createExtractors()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> =
        if (uri.scheme == "content" || uri.scheme == "local") {
            local.createExtractors(uri, responseHeaders)
        } else {
            remote.createExtractors(uri, responseHeaders)
        }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 4 — plays songs on this phone. A `local:<id>` key is opened straight from its content URI
// through the platform source: no cache, no stream resolver, no network. Every other key goes to the existing
// resolver and cache chain, unchanged. Neither source is created until it is needed, so a local song never so
// much as builds the remote chain. A local key is served only while canServe says so (access granted and the Local
// files switch not off); otherwise the open fails like a missing file, so a queue that outlives the switch plays nothing.
package com.metrolist.music.saraswati.localmedia

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.FileNotFoundException

@UnstableApi
class LocalRoutingDataSource private constructor(
    private val local: DataSource.Factory,
    private val remote: DataSource.Factory,
    private val canServe: () -> Boolean,
) : DataSource {
    class Factory(
        private val local: DataSource.Factory,
        private val remote: DataSource.Factory,
        private val canServe: () -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = LocalRoutingDataSource(local, remote, canServe)
    }

    private val listeners = ArrayList<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        active?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val key = dataSpec.key ?: dataSpec.uri.toString()
        val target: DataSource
        val spec: DataSpec
        if (LocalSongIds.isLocal(key)) {
            if (!canServe()) throw FileNotFoundException("Songs on this phone are switched off")
            val contentUri = LocalSongIds.contentUri(key) ?: throw FileNotFoundException("Not a song on this phone")
            target = local.createDataSource()
            spec = dataSpec.withUri(contentUri)
        } else {
            target = remote.createDataSource()
            spec = dataSpec
        }
        listeners.forEach(target::addTransferListener)
        active = target
        return target.open(spec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = checkNotNull(active) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        val current = active
        active = null
        current?.close()
    }
}

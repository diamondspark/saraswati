/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 11 — what the nightly run asks of the download machinery. The real one feeds Media3's
// DownloadManager directly and never starts ExoDownloadService, so no notification or foreground service appears
// at night. A request still unfinished when a run ends is stopped with [SMART_STOP_REASON], so the DownloadManager
// cannot carry it on over mobile data the next morning; only songs the ledger lists are ever stopped, resumed or
// removed, and a download the user made is never touched.
package com.metrolist.music.saraswati.offline

import androidx.media3.exoplayer.offline.Download
import com.metrolist.music.playback.DownloadUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val SMART_STOP_REASON = 5205

interface SmartDownloadExecutor {
    /** Downloads one song and waits for it; true only when it finished within [timeoutMs]. */
    suspend fun download(
        c: Candidate,
        timeoutMs: Long,
    ): Boolean

    /** True when the song is fully downloaded. */
    fun isDownloaded(id: String): Boolean

    suspend fun stop(ids: Collection<String>)

    suspend fun resume(ids: Collection<String>)

    suspend fun remove(ids: Collection<String>)

    /** Stops every song the ledger lists as unfinished. Touches the DownloadManager only if there is one. */
    suspend fun stopPending()
}

/**
 * [downloadUtil] is asked for lazily: building it opens the download database, which a cold start with nothing
 * to stop must not do.
 */
class DownloadManagerExecutor(
    private val ledger: SmartDownloadLedger,
    private val downloadUtil: () -> DownloadUtil,
) : SmartDownloadExecutor {
    override suspend fun download(
        c: Candidate,
        timeoutMs: Long,
    ): Boolean {
        val util = downloadUtil()
        val id = c.id
        val startedAt = System.currentTimeMillis()
        val request = util.prepareDownload(c.metadata) ?: return isDownloaded(id)
        onMain {
            util.downloadManager.addDownload(request)
            util.downloadManager.resumeDownloads()
        }
        val finished =
            withTimeoutOrNull(timeoutMs) {
                util.downloads.first { map ->
                    val download = map[id]
                    download != null &&
                        (
                            download.state == Download.STATE_COMPLETED ||
                                (download.state == Download.STATE_FAILED && download.updateTimeMs >= startedAt)
                        )
                }[id]
            }
        return finished?.state == Download.STATE_COMPLETED
    }

    override fun isDownloaded(id: String): Boolean =
        downloadUtil().downloads.value[id]?.state == Download.STATE_COMPLETED

    override suspend fun stop(ids: Collection<String>) = setStopReason(ids, SMART_STOP_REASON)

    override suspend fun resume(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val manager = downloadUtil().downloadManager
        onMain {
            ids.forEach { manager.setStopReason(it, Download.STOP_REASON_NONE) }
            manager.resumeDownloads()
        }
    }

    override suspend fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val manager = downloadUtil().downloadManager
        onMain { ids.forEach { manager.removeDownload(it) } }
    }

    override suspend fun stopPending() {
        val pending = ledger.pendingIds()
        if (pending.isEmpty()) return
        stop(pending)
    }

    private suspend fun setStopReason(
        ids: Collection<String>,
        reason: Int,
    ) {
        if (ids.isEmpty()) return
        val manager = downloadUtil().downloadManager
        onMain { ids.forEach { manager.setStopReason(it, reason) } }
    }

    // The DownloadManager is built for its application thread; its calls just post a message, but stay on it.
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }
}

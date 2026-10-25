package com.metrolist.music.playback

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

// SARASWATI: measurement only. Replays the production search and artist steps for the two failing matrix
// queries, outside onSetMediaItems, so the candidates and scores are visible. Logs under VOICEDIAG.
// Local-library songs that the production search also merges in are not included here.

private const val DIAG_TAG = "VOICEDIAG"
private const val SEARCH_ATTEMPTS = 6
private const val CANDIDATE_LOG_LIMIT = 15

@RunWith(AndroidJUnit4::class)
class VoiceDiagnosticInstrumentedTest {

    @Test
    fun diagnoseFailingVoiceQueries() = runBlocking {
        listOf("a r rahman songs", "o palan hare").forEach { diagnoseQuery(it) }
    }

    private suspend fun diagnoseQuery(query: String) {
        Log.i(DIAG_TAG, "query\t$query\tbegin")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val hideExplicit = target.dataStore.get(HideExplicitKey, false)
        val hideVideo = target.dataStore.get(HideVideoSongsKey, false)
        Log.i(DIAG_TAG, "settings\thideExplicit=$hideExplicit\thideVideoSongs=$hideVideo")
        var firstSongs: List<SongItem> = emptyList()
        repeat(SEARCH_ATTEMPTS) { attempt ->
            val result = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
            val items = result.getOrNull()?.items.orEmpty()
            val songs = items.filterIsInstance<SongItem>()
            // Per attempt: what the production selection would pick from this same list (findBest, else the first online song).
            val attemptBest = VoiceSearchMatcher.findBest(query, songs.map { it.toEntity() })?.song?.title
            val attemptFirst = songs.firstOrNull()?.title
            val selection = attemptBest ?: attemptFirst ?: "none"
            // Production applies these two filters before the matcher sees the list.
            val kept = songs.filterExplicit(hideExplicit).filterVideoSongs(hideVideo)
            val keptBest = VoiceSearchMatcher.findBest(query, kept.map { it.toEntity() })?.song?.title
            Log.i(
                DIAG_TAG,
                "filter\t$query\tattempt=${attempt + 1}\tkept=${kept.size}/${songs.size}\t" +
                    "findBestAfterFilters=${keptBest ?: "null"}\tpaalanKept=${kept.any { it.title.contains("Paalan", ignoreCase = true) }}",
            )
            val paalIndex = songs.indexOfFirst { it.title.contains("palan", ignoreCase = true) }
                .takeIf { it >= 0 }?.let { "#${it + 1}" } ?: "absent"
            Log.i(
                DIAG_TAG,
                "search\t$query\tattempt=${attempt + 1}\tok=${result.isSuccess}\t" +
                    "allItems=${items.size}\tsongItems=${songs.size}\t" +
                    "failure=${result.exceptionOrNull()?.describe() ?: "-"}\t" +
                    "first=$attemptFirst\tfindBest=${attemptBest ?: "null"}\tselection=$selection\t" +
                    "palanPosition=$paalIndex",
            )
            if (attempt == 0) firstSongs = songs
        }

        firstSongs.take(CANDIDATE_LOG_LIMIT).forEachIndexed { index, song ->
            Log.i(
                DIAG_TAG,
                "candidate\t$query\t#${index + 1}\t${song.title}\t" +
                    "${song.artists.joinToString(", ") { it.name }}\t${song.id}\texplicit=${song.explicit}\tvideo=${song.isVideoSong}",
            )
        }

        val candidates = firstSongs.map { it.toEntity() }
        step("rankAll\t$query") {
            val ranked = VoiceSearchMatcher.rankAll(query, candidates)
            if (ranked.isEmpty()) Log.i(DIAG_TAG, "rankAll\t$query\tno candidates")
            ranked.take(5).forEachIndexed { index, scored ->
                Log.i(
                    DIAG_TAG,
                    "rankAll\t$query\ttop${index + 1}\tscore=${scored.score}\t${scored.song.song.title}\t" +
                        scored.song.artists.joinToString(", ") { it.name },
                )
            }
            val best = VoiceSearchMatcher.findBest(query, candidates)
            Log.i(DIAG_TAG, "findBest\t$query\tresult=${best?.song?.title ?: "null (below 0.60)"}")
        }

        if (query.startsWith("a r rahman")) diagnoseArtistChain(query, firstSongs)
        Log.i(DIAG_TAG, "query\t$query\tend")
    }

    private suspend fun diagnoseArtistChain(query: String, songCandidates: List<SongItem>) {
        val fromSongs = songCandidates.flatMap { it.artists }.map { artist ->
            ArtistEntity(id = artist.id ?: artist.name.lowercase(), name = artist.name)
        }
        Log.i(DIAG_TAG, "artist\t$query\tcandidatesFromSongSearch=${fromSongs.distinctBy { it.id }.size}")

        // Diagnostic-only: the production chain can only reach the artist step when the song search returned
        // artists. This artist-filter search lets the chain run anyway, and is labelled as such in the log.
        val artistSearch = step("artist-filter search\t$query") {
            YouTube.search("a r rahman", YouTube.SearchFilter.FILTER_ARTIST)
                .getOrNull()?.items.orEmpty().filterIsInstance<ArtistItem>()
                .map { ArtistEntity(id = it.id, name = it.title) }
        }.orEmpty()
        artistSearch.forEach {
            Log.i(DIAG_TAG, "artist-filter\t$query\tname=${it.name}\tid=${it.id}\tUC=${it.id.startsWith("UC")}")
        }

        val resolved = VoiceSearchMatcher.findBestArtist(query, (fromSongs + artistSearch).distinctBy { it.id })
        Log.i(
            DIAG_TAG,
            "artist\t$query\tfindBestArtist=${resolved?.let { "name=${it.name} id=${it.id} UC=${it.id.startsWith("UC")}" } ?: "null"}",
        )
        if (resolved == null) return

        val page = step("YouTube.artist\t${resolved.id}") { YouTube.artist(resolved.id).getOrThrow() } ?: return
        val radio = page.artist.radioEndpoint
        val shuffle = page.artist.shuffleEndpoint
        val play = page.artist.playEndpoint
        val sectionSongs = page.sections.flatMap { it.items }.filterIsInstance<SongItem>().distinctBy { it.id }
        Log.i(
            DIAG_TAG,
            "artistPage\t${resolved.name}\tradio=${radio != null}\tshuffle=${shuffle != null}\tplay=${play != null}\t" +
                "sections=${page.sections.size}\tsongItems=${sectionSongs.size}",
        )
        val endpoint = radio ?: shuffle ?: play
        if (endpoint == null) {
            Log.i(DIAG_TAG, "artistPage\t${resolved.name}\tno endpoint to build a queue from")
            return
        }
        step("YouTubeQueue.getInitialStatus\t${resolved.name}") {
            val status = YouTubeQueue(endpoint).getInitialStatus()
            Log.i(DIAG_TAG, "radioQueue\t${resolved.name}\titems=${status.items.size}")
        }
    }

    private fun SongItem.toEntity(): Song =
        Song(
            song = SongEntity(id = id, title = title, duration = duration ?: -1, thumbnailUrl = thumbnail),
            artists = artists.map { ArtistEntity(id = it.id ?: it.name.lowercase(), name = it.name) },
            album = null,
        )

    // Runs one step and logs any exception with its class and message, so later steps still run.
    private suspend fun <T> step(label: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            Log.i(DIAG_TAG, "EXCEPTION\t$label\t${e.describe()}")
            null
        }

    private fun Throwable.describe(): String = "${javaClass.name}: $message"
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 21 — the review step of the Spotify import, built from the approved
// mockup (mockups/project/SpotifyImport.dc.html). Each unmatched song gets "Use this" or "Skip";
// a song that is not looked at is skipped when the import finishes.
package com.metrolist.music.saraswati.spotifyimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Decision {
    data class Use(
        val result: ImportedSongResult,
    ) : Decision

    data object Skip : Decision
}

private enum class SearchFrom { PHONE, YOUTUBE }

@Composable
fun ImportReviewStep(
    review: SpotifyImportUiState.Review,
    onFinish: (List<ImportedSongResult>) -> Unit,
) {
    val decisions = remember { mutableStateMapOf<String, Decision>() }
    val searcher = remember { ImportReplacementSearcher() }
    val scope = rememberCoroutineScope()
    var openKey by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<Song>>(emptyList()) }
    var candidateFrom by remember { mutableStateOf<SearchFrom?>(null) }
    var searching by remember { mutableStateOf(false) }
    var noResults by remember { mutableStateOf(false) }

    fun runSearch(from: SearchFrom) {
        val text = query.trim()
        if (text.isEmpty()) return
        searching = true
        noResults = false
        candidateFrom = from
        scope.launch {
            val found =
                when (from) {
                    SearchFrom.PHONE ->
                        withContext(Dispatchers.Default) {
                            searcher.searchLocal(text, review.localLibrary)
                        }
                    SearchFrom.YOUTUBE -> searcher.searchYouTube(text)
                }
            candidates = found
            noResults = found.isEmpty()
            searching = false
        }
    }

    val chosenCount = decisions.values.count { it is Decision.Use }
    val skippedCount = decisions.values.count { it is Decision.Skip }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text = stringResource(R.string.spotify_import_review_title, review.items.size),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.spotify_import_review_desc, review.summary.matched),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        items(review.items, key = { it.originalSong.id }) { result ->
            val key = result.originalSong.id
            val decision = decisions[key]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = result.originalSong.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = result.originalSong.artists.joinToString(" · ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (decision) {
                        is Decision.Use -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.spotify_import_review_using, decision.result.resolvedSong?.title.orEmpty()),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { decisions.remove(key) }) {
                                Text(stringResource(R.string.spotify_import_review_undo))
                            }
                        }
                        Decision.Skip -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.spotify_import_review_skipped),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { decisions.remove(key) }) {
                                Text(stringResource(R.string.spotify_import_review_undo))
                            }
                        }
                        null -> if (openKey == key) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.spotify_import_review_query)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { runSearch(SearchFrom.PHONE) },
                                    enabled = query.isNotBlank() && !searching,
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.spotify_import_review_local)) }
                                Button(
                                    onClick = { runSearch(SearchFrom.YOUTUBE) },
                                    enabled = query.isNotBlank() && !searching,
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.spotify_import_review_youtube)) }
                            }
                            if (noResults) {
                                Text(
                                    text = stringResource(R.string.spotify_import_review_no_results),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            candidates.forEach { candidate ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = candidate.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Text(
                                            text = candidate.artists.joinToString(" · ") { it.name },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            val source = if (candidateFrom == SearchFrom.PHONE) ImportSource.LOCAL else ImportSource.YOUTUBE
                                            decisions[key] =
                                                Decision.Use(
                                                    ImportedSongResult(
                                                        originalSong = result.originalSong,
                                                        resolvedId = candidate.id,
                                                        resolvedSong = candidate,
                                                        source = source,
                                                    ),
                                                )
                                            openKey = null
                                        },
                                    ) { Text(stringResource(R.string.spotify_import_review_use)) }
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        openKey = key
                                        query = result.originalSong.title
                                        candidates = emptyList()
                                        noResults = false
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.spotify_import_review_search)) }
                                OutlinedButton(
                                    onClick = { decisions[key] = Decision.Skip },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.spotify_import_review_skip)) }
                            }
                        }
                    }
                }
            }
        }
        item(key = "footer") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        onFinish(decisions.values.filterIsInstance<Decision.Use>().map { it.result })
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text(stringResource(R.string.spotify_import_finish))
                }
                Text(
                    text =
                        stringResource(
                            R.string.spotify_import_review_summary,
                            chosenCount,
                            skippedCount,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

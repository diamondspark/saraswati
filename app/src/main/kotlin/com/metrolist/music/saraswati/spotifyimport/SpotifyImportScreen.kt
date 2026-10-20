/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 21 — the Spotify import screen, built to the approved mockup
// (mockups/project/SpotifyImport.dc.html). Four steps: file, matching, review, done.
package com.metrolist.music.saraswati.spotifyimport

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.R
import java.io.IOException

const val SPOTIFY_IMPORT_ROUTE = "spotify_import"

private val IMPORT_MIME_TYPES =
    arrayOf("application/zip", "application/json", "text/csv", "text/comma-separated-values")

@Composable
fun SpotifyImportScreen(
    navController: NavController,
    viewModel: SpotifyImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val name = displayName(context, uri)
                viewModel.onFilePicked(name) {
                    context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
                }
            }
        }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                text = stringResource(R.string.spotify_import_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        val step = stepOf(state)
        if (step > 0) {
            StepBars(step = step)
        }

        when (val current = state) {
            is SpotifyImportUiState.Pick -> PickStep(current.error, onChoose = { picker.launch(IMPORT_MIME_TYPES) })
            SpotifyImportUiState.Reading -> CenteredLoading()
            is SpotifyImportUiState.Chosen ->
                ChosenStep(
                    current = current,
                    onToggle = viewModel::togglePlaylist,
                    onStart = viewModel::startMatching,
                    onChangeFile = { picker.launch(IMPORT_MIME_TYPES) },
                )
            is SpotifyImportUiState.Matching ->
                MatchingStep(current, onCancel = viewModel::cancel)
            is SpotifyImportUiState.Review ->
                ImportReviewStep(
                    review = current,
                    onFinish = viewModel::confirmReview,
                )
            is SpotifyImportUiState.Done ->
                DoneStep(
                    done = current,
                    onOpenPlaylist = { id -> navController.navigate("local_playlist/$id") },
                    onAnother = viewModel::restart,
                )
            is SpotifyImportUiState.Failed -> FailedStep(current.message, onAnother = viewModel::restart)
        }
    }
}

private fun stepOf(state: SpotifyImportUiState): Int =
    when (state) {
        is SpotifyImportUiState.Pick, SpotifyImportUiState.Reading, is SpotifyImportUiState.Chosen -> 1
        is SpotifyImportUiState.Matching -> 2
        is SpotifyImportUiState.Review -> 3
        is SpotifyImportUiState.Done -> 4
        is SpotifyImportUiState.Failed -> 0
    }

@Composable
private fun StepBars(step: Int) {
    val names =
        listOf(
            R.string.spotify_import_step_file,
            R.string.spotify_import_step_matching,
            R.string.spotify_import_step_review,
            R.string.spotify_import_step_done,
        )
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            names.forEachIndexed { index, _ ->
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(
                                if (index + 1 <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                )
            }
        }
        Text(
            text = stringResource(R.string.spotify_import_step_text, step, stringResource(names[step - 1])),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PickStep(
    error: Int?,
    onChoose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.spotify_import_pick_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(text = stringResource(R.string.spotify_import_pick_request), style = MaterialTheme.typography.bodyLarge)
        Text(text = stringResource(R.string.spotify_import_pick_choose), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.spotify_import_pick_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onChoose, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.spotify_import_choose_file))
        }
        Text(
            text = stringResource(R.string.spotify_import_phone_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun ChosenStep(
    current: SpotifyImportUiState.Chosen,
    onToggle: (Int) -> Unit,
    onStart: () -> Unit,
    onChangeFile: () -> Unit,
) {
    val selected = current.playlists.filter { it.selected }
    Column(modifier = Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = current.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text =
                stringResource(
                    R.string.spotify_import_chosen_summary,
                    current.playlists.size,
                    current.playlists.sumOf { it.trackCount },
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (current.cut) {
            Text(
                text = stringResource(R.string.spotify_import_cut_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(current.playlists, key = { it.index }) { playlist ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggle(playlist.index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = playlist.selected, onCheckedChange = { onToggle(playlist.index) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            text = stringResource(R.string.spotify_import_playlist_songs, playlist.trackCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onChangeFile, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.spotify_import_choose_other))
        }
        Button(
            onClick = onStart,
            enabled = selected.any { it.trackCount > 0 },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.spotify_import_start))
        }
    }
}

@Composable
private fun MatchingStep(
    current: SpotifyImportUiState.Matching,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.spotify_import_matching_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.spotify_import_matching_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        val fraction = if (current.total == 0) 0f else current.done.toFloat() / current.total
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        Text(
            text = stringResource(R.string.spotify_import_progress, current.done, current.total),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 18.dp)) {
            Text(stringResource(R.string.spotify_import_cancel))
        }
    }
}

@Composable
private fun DoneStep(
    done: SpotifyImportUiState.Done,
    onOpenPlaylist: (String) -> Unit,
    onAnother: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = stringResource(R.string.spotify_import_done_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            text = stringResource(R.string.spotify_import_done_summary, done.imported),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (done.skipped > 0) {
            Text(
                text = stringResource(R.string.spotify_import_done_skipped, done.skipped),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (done.summary.overLimit > 0) {
            Text(
                text = stringResource(R.string.spotify_import_done_overlimit, done.summary.overLimit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (done.summary.cut) {
            Text(
                text = stringResource(R.string.spotify_import_cut_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (done.summary.playlistIds.size == 1) {
                Button(onClick = { onOpenPlaylist(done.summary.playlistIds.first()) }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Text(stringResource(R.string.spotify_import_open_playlist))
                }
            }
            OutlinedButton(onClick = onAnother, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(stringResource(R.string.spotify_import_another))
            }
        }
    }
}

@Composable
private fun FailedStep(
    message: Int,
    onAnother: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = stringResource(message), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onAnother, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.spotify_import_another))
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private fun displayName(
    context: android.content.Context,
    uri: android.net.Uri,
): String =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment
        ?: "spotify-export"

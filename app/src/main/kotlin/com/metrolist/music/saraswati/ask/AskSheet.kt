/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 6 Task 11 — the Poochho Saraswati sheet. The answer is plain Text(String): never linkified,
// never rendered as Markdown or HTML. The speech prompt is the phone's own; a voice result is sent at once.
package com.metrolist.music.saraswati.ask

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.music.R
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.saraswati.family.Station
import com.metrolist.music.saraswati.family.parivaar.MixTrack
import com.metrolist.music.saraswati.saral.PlayerConnectionPlayback
import com.metrolist.music.utils.SearchRoutes
import java.util.Locale

/** The sheet's actions in the app: a search, the player, or the Sangat section. Each one closes the sheet. */
private fun askActionsFor(
    navController: NavController,
    playerConnection: PlayerConnection?,
    close: () -> Unit,
): AskActions =
    object : AskActions {
        override fun openSearch(query: String) {
            close()
            navController.navigate(SearchRoutes.resultRoute(query))
        }

        override fun playStation(station: Station) {
            close()
            playerConnection?.let { PlayerConnectionPlayback(it).playRadio(station.seedVideoId) }
        }

        override fun playParivaarMix(tracks: List<MixTrack>) {
            close()
            playerConnection?.let { PlayerConnectionPlayback(it).playParivaarMix(tracks) }
        }

        override fun openSangat() {
            close()
            navController.navigate("family")
        }
    }

private fun voiceIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "")
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(
    navController: NavController,
    playerConnection: PlayerConnection?,
    onClose: () -> Unit,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val actions = remember(navController, playerConnection) { askActionsFor(navController, playerConnection, onClose) }
    LaunchedEffect(actions) { viewModel.attach(actions) }

    val state by viewModel.state.collectAsState()
    var text by remember { mutableStateOf("") }
    var voiceUnavailable by remember { mutableStateOf(false) }
    val limited = state is AskUiState.Limit
    val busy = state == AskUiState.Busy

    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (heard != null) viewModel.onVoiceResult(heard)
        }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    enabled = !limited,
                    singleLine = true,
                    label = { Text(stringResource(R.string.ask_field_label)) },
                    placeholder = { Text(stringResource(R.string.ask_hint)) },
                )
                IconButton(
                    enabled = !limited && !busy,
                    onClick = {
                        voiceUnavailable = false
                        try {
                            voiceLauncher.launch(voiceIntent())
                        } catch (e: ActivityNotFoundException) {
                            voiceUnavailable = true
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mic),
                        contentDescription = stringResource(R.string.ask_voice_desc),
                    )
                }
            }
            if (voiceUnavailable) {
                Text(stringResource(R.string.ask_voice_unavailable), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.send(text) },
                enabled = !limited && !busy && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ask_send))
            }
            Spacer(Modifier.height(16.dp))

            when (val current = state) {
                AskUiState.Idle -> Text(stringResource(R.string.ask_idle), style = MaterialTheme.typography.bodyMedium)
                AskUiState.Busy -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.ask_thinking), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is AskUiState.Answer -> {
                    // Plain text by design: the hub's words are shown as they are, never linkified or rendered.
                    Text(current.text, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = viewModel::searchThis, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ask_search_this))
                    }
                }
                is AskUiState.Limit -> {
                    Text(stringResource(R.string.ask_limit_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.ask_limit_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = viewModel::searchThis, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ask_search_instead))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.ask_disclaimer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

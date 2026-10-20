/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 5 Task 3 — the Local files switch in Privacy settings. Turning it on without access first shows
// what Saraswati reads and that the files stay on the phone, and only then the system permission prompt. Turning
// it off stops every scan and read and hides the songs already found.
package com.metrolist.music.saraswati.localmedia

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.metrolist.music.R
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalFilesSettingsViewModel
    @Inject
    constructor(
        private val repository: LocalSongsRepository,
    ) : ViewModel() {
        /** The saved switch; null until the person has touched it. */
        val stored: StateFlow<Boolean?> =
            repository.storedSwitch.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** Call only once access is GRANTED: turns the switch on and looks for songs. */
        fun turnOn() {
            viewModelScope.launch(Dispatchers.IO) {
                repository.setEnabled(true)
                repository.syncNow()
            }
        }

        fun turnOff() {
            viewModelScope.launch(Dispatchers.IO) { repository.setEnabled(false) }
        }
    }

@Composable
fun LocalFilesSettingsGroup(viewModel: LocalFilesSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(localAccess(context)) }
    var showExplanation by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = localAccess(context) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            access = localAccess(context)
            refused = !granted
            if (granted) viewModel.turnOn()
        }

    val on = LocalFiles.enabled(stored, access)

    if (showExplanation) {
        DefaultDialog(
            onDismiss = { showExplanation = false },
            title = { Text(stringResource(R.string.local_files_explain_title)) },
            content = {
                Text(
                    text = stringResource(R.string.local_files_explain_body),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(onClick = { showExplanation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        showExplanation = false
                        permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                    },
                ) {
                    Text(stringResource(R.string.local_files_explain_continue))
                }
            },
        )
    }

    Material3SettingsGroup(
        title = stringResource(R.string.local_files_title),
        items =
            listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text(stringResource(R.string.local_files_title)) },
                    description = { Text(stringResource(localFilesDescription(access, on, refused))) },
                    trailingContent = {
                        Switch(
                            checked = on,
                            enabled = access != LocalAccess.UNSUPPORTED,
                            onCheckedChange = null,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(if (on) R.drawable.check else R.drawable.close),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    enabled = access != LocalAccess.UNSUPPORTED,
                    onClick = {
                        when {
                            on -> viewModel.turnOff()
                            access == LocalAccess.GRANTED -> viewModel.turnOn()
                            else -> showExplanation = true
                        }
                    },
                ),
            ),
    )
}

internal fun localFilesDescription(
    access: LocalAccess,
    on: Boolean,
    refused: Boolean,
): Int =
    when {
        access == LocalAccess.UNSUPPORTED -> R.string.local_files_unsupported
        on -> R.string.local_files_desc_on
        refused && access == LocalAccess.NOT_GRANTED -> R.string.local_files_refused
        else -> R.string.local_files_desc_off
    }

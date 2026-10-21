/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: feedback — Settings, System, "Send feedback": a bug or an idea in the person's own words, with the
// app and phone info shown (and switchable off) before anything is sent. Every failure is one plain sentence.
package com.metrolist.music.saraswati.family.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain

@Composable
fun FeedbackScreen(
    navController: NavController,
    viewModel: FeedbackViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.feedback_title)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = stringResource(R.string.back_button_desc),
                    )
                }
            },
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state == FeedbackUiState.Sent) {
                SentContent(onSendAnother = viewModel::sendAnother)
            } else {
                FormContent(
                    draft = draft,
                    state = state,
                    diagnostics = viewModel.diagnostics,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun SentContent(onSendAnother: () -> Unit) {
    Text(stringResource(R.string.feedback_thanks_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.feedback_thanks_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onSendAnother) {
        Text(stringResource(R.string.feedback_send_another))
    }
}

@Composable
private fun FormContent(
    draft: FeedbackDraft,
    state: FeedbackUiState,
    diagnostics: FeedbackDiagnostics,
    viewModel: FeedbackViewModel,
) {
    val sending = state == FeedbackUiState.Sending
    val length = draft.trimmed.length
    val tooLong = length > FEEDBACK_TEXT_MAX

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        FeedbackKind.entries.forEachIndexed { index, kind ->
            SegmentedButton(
                selected = draft.kind == kind,
                onClick = { viewModel.setKind(kind) },
                enabled = !sending,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = FeedbackKind.entries.size),
                label = {
                    Text(stringResource(if (kind == FeedbackKind.Bug) R.string.feedback_kind_bug else R.string.feedback_kind_idea))
                },
            )
        }
    }

    OutlinedTextField(
        value = draft.text,
        onValueChange = viewModel::setText,
        enabled = !sending,
        label = {
            Text(stringResource(if (draft.kind == FeedbackKind.Bug) R.string.feedback_label_bug else R.string.feedback_label_idea))
        },
        supportingText = {
            Text(
                text = stringResource(R.string.feedback_counter, length, FEEDBACK_TEXT_MAX),
                modifier = Modifier.fillMaxWidth(),
                color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        isError = tooLong,
        minLines = 5,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.feedback_include_info),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = draft.includeInfo,
            onCheckedChange = viewModel::setIncludeInfo,
            enabled = !sending,
        )
    }
    Column {
        if (draft.includeInfo) {
            InfoLine(stringResource(R.string.feedback_info_app, diagnostics.appVersion))
            InfoLine(stringResource(R.string.feedback_info_android, diagnostics.android))
            InfoLine(stringResource(R.string.feedback_info_phone, diagnostics.device))
        } else {
            InfoLine(stringResource(R.string.feedback_info_off))
        }
    }

    Text(
        stringResource(R.string.feedback_privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state is FeedbackUiState.Error) {
        Text(
            stringResource(errorMessage(state.reason)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = viewModel::send, enabled = draft.isValid && !sending) {
            Text(stringResource(R.string.feedback_send))
        }
        if (sending) {
            CircularProgressIndicator(Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun errorMessage(reason: FeedbackFailure): Int =
    when (reason) {
        FeedbackFailure.NotPaired -> R.string.feedback_error_not_paired
        FeedbackFailure.KeyRejected -> R.string.feedback_inbox_error_key_rejected
        FeedbackFailure.Offline -> R.string.feedback_error_offline
        FeedbackFailure.RateLimited -> R.string.feedback_error_rate_limited
        FeedbackFailure.NotSwitchedOn -> R.string.feedback_error_unavailable
        FeedbackFailure.Invalid -> R.string.feedback_error_invalid
        FeedbackFailure.Other -> R.string.feedback_error_other
    }

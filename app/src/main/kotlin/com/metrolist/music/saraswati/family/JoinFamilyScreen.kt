/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — the claiming phone's screens: take the pairing link
// (opened as a deep link, or pasted), name the phone and say who it is for,
// send the request, wait for the owner's approval.
//
// The role cards follow the approved onboarding mockup: "Adult" and
// "दादा-दादी · नाना-नानी" (grandparent). The pairing token is never kept in
// saved instance state: not the token in the view model, and not the link or
// token pasted into the field either, which is plain remember. Only the phone's
// display name and the role card picked are saved across process death.
package com.metrolist.music.saraswati.family

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinFamilyScreen(
    navController: NavController,
    viewModel: JoinFamilyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // On open and on every return to this screen, so a second link tapped while it is
    // already open is picked up (start() is safe to repeat).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.start() }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

            val current = state
            // Ready, Claiming and a passing failure (offline, busy) share one call site so the
            // typed name and chosen role survive the request.
            val holdsLink =
                current is JoinUiState.Ready ||
                    current is JoinUiState.Claiming ||
                    (current is JoinUiState.Failed && !current.error.needsNewLink())
            when {
                holdsLink ->
                    PickRole(
                        error = (current as? JoinUiState.Failed)?.error,
                        busy = current is JoinUiState.Claiming,
                        onSend = viewModel::claim,
                    )
                current is JoinUiState.Failed -> PasteLink(error = current.error, onSubmit = viewModel::submitInput)
                current is JoinUiState.Waiting -> Waiting()
                current is JoinUiState.Approved -> Approved(role = current.role, onDone = { navController.popBackStack() })
                current is JoinUiState.AlreadyPaired ->
                    Message(
                        title = R.string.family_paired_title,
                        body = R.string.family_join_already,
                        iconRes = R.drawable.check,
                        onDone = { navController.popBackStack() },
                    )
                else -> PasteLink(error = null, onSubmit = viewModel::submitInput)
            }
            Spacer(Modifier.height(16.dp))
        }

        TopAppBar(
            title = { Text(stringResource(R.string.family_join_title)) },
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
    }
}

/** The link itself is no good (bad, expired, used or unknown), so the person needs a different one. */
private fun FamilyError.needsNewLink(): Boolean =
    this == FamilyError.INVALID_INPUT || this == FamilyError.GONE || this == FamilyError.NOT_FOUND

@Composable
private fun PasteLink(
    error: FamilyError?,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }

    Text(stringResource(R.string.family_join_heading), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.family_join_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    error?.let { FamilyErrorBanner(it) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.family_join_field)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { clipboardText(context)?.let { text = it } }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.family_join_paste))
        }
        Button(onClick = { onSubmit(text) }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.family_join_continue))
        }
    }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !clipboard.hasPrimaryClip()) return null
    return clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
}

@Composable
private fun PickRole(
    error: FamilyError?,
    busy: Boolean,
    onSend: (name: String, role: DeviceRole) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(Build.MODEL.orEmpty()) }
    var role by rememberSaveable { mutableStateOf<DeviceRole?>(null) }

    Text(stringResource(R.string.family_join_who), style = MaterialTheme.typography.headlineSmall)
    error?.let { FamilyErrorBanner(it) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(JoinFamilyViewModel.MAX_NAME_LENGTH) },
        label = { Text(stringResource(R.string.family_join_name_label)) },
        supportingText = { Text(stringResource(R.string.family_join_name_hint)) },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.selectableGroup()) {
        RoleCard(
            title = stringResource(R.string.family_role_adult),
            description = stringResource(R.string.family_role_adult_desc),
            selected = role == DeviceRole.ADULT,
            enabled = !busy,
            onClick = { role = DeviceRole.ADULT },
        )
        RoleCard(
            title = stringResource(R.string.family_role_grandparent_hindi),
            description = stringResource(R.string.family_role_grandparent) + ": " + stringResource(R.string.family_role_grandparent_desc),
            selected = role == DeviceRole.GRANDPARENT,
            enabled = !busy,
            onClick = { role = DeviceRole.GRANDPARENT },
        )
    }
    Button(
        onClick = { role?.let { onSend(name, it) } },
        enabled = role != null && name.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (busy) R.string.family_join_sending else R.string.family_join_send))
    }
}

@Composable
private fun RoleCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Waiting() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.family_join_waiting_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.family_join_waiting_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Approved(
    role: DeviceRole,
    onDone: () -> Unit,
) {
    Message(
        title = R.string.family_join_done_title,
        body = if (role == DeviceRole.GRANDPARENT) R.string.family_join_done_grandparent else R.string.family_join_done_adult,
        iconRes = R.drawable.check,
        onDone = onDone,
    )
}

@Composable
private fun Message(
    title: Int,
    body: Int,
    iconRes: Int,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onDone) { Text(stringResource(R.string.family_join_close)) }
    }
}

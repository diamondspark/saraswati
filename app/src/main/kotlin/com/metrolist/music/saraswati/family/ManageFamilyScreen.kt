/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 7 — Manage Family, the owner's screen: make a pairing link
// or QR code, approve or deny the phones that asked to join, see how each
// family phone is doing (including what time it is there), switch a phone
// between adult and grandparent mode, remove a phone.
//
// Task 8 adds the station editor (name, YouTube video, icon, order; one to six
// tiles) and the four family-feature switches below the phones.
//
// On a phone that is not the owner's, the same route shows the one-time
// owner setup instead: paste the admin key, which the hub must accept before
// it is kept. The admin key is held in a plain remember, never rememberSaveable,
// so it does not end up in saved instance state.
package com.metrolist.music.saraswati.family

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.IsFamilyOwnerKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay

private const val ROSTER_POLL_MS = 10_000L
private const val RATE_LIMIT_PAUSE_MS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFamilyScreen(
    navController: NavController,
    viewModel: FamilyViewModel = hiltViewModel(),
) {
    val (isOwner) = rememberPreference(IsFamilyOwnerKey, defaultValue = false)
    val error by viewModel.error.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // An owner flag without its key (a restored backup keeps the flag, never the key) falls back to setup.
        if (isOwner && error != FamilyError.NO_ADMIN_KEY) {
            OwnerContent(viewModel)
        } else {
            OwnerSetupContent(viewModel)
        }

        TopAppBar(
            title = { Text(stringResource(R.string.family_manage_title)) },
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

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                ).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

// --- Owner setup ------------------------------------------------------

@Composable
private fun OwnerSetupContent(viewModel: FamilyViewModel) {
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val setup by viewModel.setupState.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    val checking = setup is OwnerSetupState.Checking

    ScreenColumn {
        SectionCard {
            Text(
                text = stringResource(R.string.family_owner_setup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.family_owner_setup_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.family_admin_key_label)) },
                singleLine = true,
                enabled = !checking,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            (setup as? OwnerSetupState.Failed)?.let { FamilyErrorBanner(it.error) }
            Button(
                // SARASWATI: fix wave A (M7) — the key was most likely pasted from the clipboard, which would
                // otherwise keep it for any other app to read once it has been saved.
                onClick = { viewModel.saveAdminKey(key) { saved -> ClipboardHygiene.clearIfHolds(clipboard, saved) } },
                enabled = key.isNotBlank() && !checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(if (checking) R.string.family_admin_key_checking else R.string.family_admin_key_save),
                )
            }
        }
    }
}

// --- Owner ------------------------------------------------------------

@Composable
private fun OwnerContent(viewModel: FamilyViewModel) {
    val pairing by viewModel.pairingState.collectAsStateWithLifecycle()
    val roster by viewModel.roster.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val stationsState by viewModel.stationsState.collectAsStateWithLifecycle()
    val killSwitches by viewModel.killSwitches.collectAsStateWithLifecycle()

    // The hub allows 20 admin requests a minute in all; opening the screen
    // uses 4 and this loop 6 a minute, and it pauses for a minute after a 429.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshOwnerData()
            viewModel.loadSettings()
            while (true) {
                delay(if (viewModel.error.value == FamilyError.RATE_LIMITED) RATE_LIMIT_PAUSE_MS else ROSTER_POLL_MS)
                viewModel.refreshRoster()
            }
        }
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    var confirmRole by remember { mutableStateOf<Pair<RosterEntry, DeviceRole>?>(null) }
    var confirmRevoke by remember { mutableStateOf<RosterEntry?>(null) }
    var editing by remember { mutableStateOf<StationEdit?>(null) }
    var confirmRemoveStation by remember { mutableStateOf<Station?>(null) }

    confirmRole?.let { (entry, role) ->
        val name = entry.deviceName ?: stringResource(R.string.family_unnamed_phone)
        ConfirmDialog(
            message =
                stringResource(
                    if (role == DeviceRole.GRANDPARENT) R.string.family_switch_grandparent_confirm else R.string.family_switch_adult_confirm,
                    name,
                ),
            confirmLabel = stringResource(role.switchLabelRes()),
            onConfirm = {
                viewModel.setRole(entry.deviceId, role)
                confirmRole = null
            },
            onDismiss = { confirmRole = null },
        )
    }
    confirmRevoke?.let { entry ->
        val name = entry.deviceName ?: stringResource(R.string.family_unnamed_phone)
        ConfirmDialog(
            message = stringResource(R.string.family_revoke_confirm, name),
            confirmLabel = stringResource(R.string.family_revoke),
            onConfirm = {
                viewModel.revoke(entry.deviceId)
                confirmRevoke = null
            },
            onDismiss = { confirmRevoke = null },
        )
    }

    editing?.let { edit ->
        StationDialog(
            initial = edit.station,
            onSave = { title, video, icon ->
                if (edit.station == null) {
                    viewModel.addStation(title, video, icon)
                } else {
                    viewModel.updateStation(edit.station.id, title, video, icon)
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    confirmRemoveStation?.let { station ->
        ConfirmDialog(
            message = stringResource(R.string.family_station_remove_confirm, station.title),
            confirmLabel = stringResource(R.string.family_station_remove),
            onConfirm = {
                viewModel.removeStation(station.id)
                confirmRemoveStation = null
            },
            onDismiss = { confirmRemoveStation = null },
        )
    }

    val pending = roster.filter { it.status == STATUS_PENDING }
    val approved = roster.filter { it.status == STATUS_APPROVED }
    val reports = health.associateBy { it.deviceId }

    ScreenColumn {
        error?.let { FamilyErrorBanner(it) }

        SectionCard {
            Text(stringResource(R.string.family_pairing_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.family_pairing_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PairingSection(pairing = pairing, onCreate = viewModel::startPairing)
        }

        SectionCard {
            Text(stringResource(R.string.family_requests_title), style = MaterialTheme.typography.titleMedium)
            if (pending.isEmpty()) {
                Text(
                    stringResource(R.string.family_requests_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pending.isNotEmpty()) {
                Text(
                    stringResource(R.string.family_requests_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            pending.forEach { entry ->
                PendingRow(
                    entry = entry,
                    now = now,
                    onApprove = { viewModel.approve(entry.deviceId) },
                    onDeny = { viewModel.revoke(entry.deviceId) },
                )
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.family_phones_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = viewModel::refreshOwnerData,
                    onLongClick = {},
                ) {
                    Icon(painterResource(R.drawable.refresh), contentDescription = stringResource(R.string.family_refresh))
                }
            }
            if (approved.isEmpty()) {
                Text(
                    stringResource(R.string.family_phones_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            approved.forEach { entry ->
                PhoneRow(
                    entry = entry,
                    report = reports[entry.deviceId],
                    now = now,
                    onSwitchRole = { confirmRole = entry to entry.role.other() },
                    onRemove = { confirmRevoke = entry },
                )
            }
        }

        SectionCard {
            Text(stringResource(R.string.family_stations_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.family_stations_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stationsState == StationsState.NOT_LOADED && error == null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            stations.forEachIndexed { index, station ->
                StationRow(
                    station = station,
                    canMoveUp = index > 0,
                    canMoveDown = index < stations.lastIndex,
                    onMoveUp = { viewModel.moveStation(station.id, -1) },
                    onMoveDown = { viewModel.moveStation(station.id, +1) },
                    onEdit = { editing = StationEdit(station) },
                    onRemove = { confirmRemoveStation = station },
                )
            }
            if (stationsState == StationsState.DRAFT) {
                Text(
                    stringResource(R.string.family_stations_draft),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stationsState != StationsState.NOT_LOADED && stations.size < FamilyViewModel.MAX_STATIONS) {
                OutlinedButton(onClick = { editing = StationEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.family_station_add))
                }
            }
        }

        SectionCard {
            Text(stringResource(R.string.family_features_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.family_features_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FamilyFeature.entries.forEach { feature ->
                FeatureRow(
                    feature = feature,
                    enabled = FamilyFeature.isEnabled(killSwitches, feature),
                    onToggle = { viewModel.toggleKillSwitch(feature.key, it) },
                )
            }
        }
    }
}

/** The station being edited, or null for a new one. A class so "new" and "none" are different states. */
private class StationEdit(
    val station: Station?,
)

@Composable
private fun StationRow(
    station: Station,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painter = painterResource(StationIcons.drawableFor(station.iconIndex)),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(station.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (station.seedVideoId.isBlank()) {
                    Text(
                        stringResource(R.string.family_station_needs_video),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            M3IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(painterResource(R.drawable.arrow_upward), contentDescription = stringResource(R.string.family_station_move_up))
            }
            M3IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(painterResource(R.drawable.arrow_downward), contentDescription = stringResource(R.string.family_station_move_down))
            }
            M3IconButton(onClick = onEdit) {
                Icon(painterResource(R.drawable.edit), contentDescription = stringResource(R.string.family_station_edit))
            }
            M3IconButton(onClick = onRemove) {
                Icon(
                    painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.family_station_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationDialog(
    initial: Station?,
    onSave: (title: String, video: String, iconIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var video by remember { mutableStateOf(initial?.seedVideoId.orEmpty()) }
    var icon by remember { mutableStateOf(initial?.iconIndex?.coerceIn(0, StationIcons.COUNT - 1) ?: 0) }

    val videoOk = StationVideo.videoIdFrom(video) != null
    // Editing may leave the field empty to keep the station's current video.
    val videoAcceptable = videoOk || (initial != null && video.isBlank())
    val canSave = title.isNotBlank() && videoAcceptable

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.family_station_add_title else R.string.family_station_edit_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(FamilyViewModel.MAX_TITLE_LENGTH) },
                    label = { Text(stringResource(R.string.family_station_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = video,
                    onValueChange = { video = it },
                    label = { Text(stringResource(R.string.family_station_video_label)) },
                    isError = video.isNotBlank() && !videoOk,
                    supportingText = {
                        Text(
                            stringResource(
                                if (video.isNotBlank() && !videoOk) R.string.family_station_video_invalid else R.string.family_station_video_hint,
                            ),
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.family_station_icon_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.selectableGroup(),
                ) {
                    repeat(StationIcons.COUNT) { index ->
                        val selected = index == icon
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .border(
                                        BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                        CircleShape,
                                    ).selectable(selected = selected, role = Role.RadioButton, onClick = { icon = index }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(StationIcons.drawableFor(index)),
                                contentDescription = stringResource(R.string.family_station_icon_option, index + 1),
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, video, icon) }, enabled = canSave) {
                Text(stringResource(R.string.family_station_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FeatureRow(
    feature: FamilyFeature,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val (title, description) =
        when (feature) {
            FamilyFeature.SANGAT -> R.string.family_feature_sangat to R.string.family_feature_sangat_desc
            FamilyFeature.PARIVAAR_MIX -> R.string.family_feature_parivaar_mix to R.string.family_feature_parivaar_mix_desc
            FamilyFeature.FAMILY_PLAYLISTS -> R.string.family_feature_family_playlists to R.string.family_feature_family_playlists_desc
            FamilyFeature.CAST_RELAY -> R.string.family_feature_cast_relay to R.string.family_feature_cast_relay_desc
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

private const val STATUS_PENDING = "pending"
private const val STATUS_APPROVED = "approved"

private fun DeviceRole.other(): DeviceRole = if (this == DeviceRole.ADULT) DeviceRole.GRANDPARENT else DeviceRole.ADULT

private fun DeviceRole.switchLabelRes(): Int =
    if (this == DeviceRole.GRANDPARENT) R.string.family_switch_to_grandparent else R.string.family_switch_to_adult

@Composable
private fun ConfirmDialog(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// --- Pairing ----------------------------------------------------------

@Composable
private fun PairingSection(
    pairing: PairingUiState,
    onCreate: () -> Unit,
) {
    when (pairing) {
        is PairingUiState.Creating ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.family_pairing_creating))
            }

        is PairingUiState.Ready -> {
            PairingReady(link = pairing.link)
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.family_pairing_create))
            }
        }

        else -> {
            (pairing as? PairingUiState.Error)?.let { FamilyErrorBanner(it.error) }
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.family_pairing_create))
            }
        }
    }
}

@Composable
private fun PairingReady(link: String) {
    val context = LocalContext.current
    val qr = remember(link) { QrCode.encode(link) }
    val qrDescription = stringResource(R.string.family_pairing_qr_desc)

    if (qr != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrImage(
                matrix = qr,
                modifier =
                    Modifier
                        .widthIn(max = 260.dp)
                        .fillMaxWidth()
                        .semantics { contentDescription = qrDescription },
            )
        }
    }
    Text(
        stringResource(R.string.family_pairing_ready),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { copyLink(context, link) }, modifier = Modifier.weight(1f)) {
            Icon(painterResource(R.drawable.content_copy), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.copy))
        }
        Button(onClick = { shareLink(context, link) }, modifier = Modifier.weight(1f)) {
            Icon(painterResource(R.drawable.share), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.share))
        }
    }
}

/** The modules in black on white, inside a quiet zone of its own so it scans against any theme. */
@Composable
private fun QrImage(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(18.dp),
    ) {
        val cell = size.width / matrix.size
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
                }
            }
        }
    }
}

private fun copyLink(
    context: Context,
    link: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Family pairing link", link)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    // Android 13 and later confirm a copy themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.family_pairing_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun shareLink(
    context: Context,
    link: String,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.family_pairing_share_message, link))
        }
    context.startActivity(Intent.createChooser(send, null))
}

// --- Rows -------------------------------------------------------------

@Composable
private fun roleSummary(role: DeviceRole): String =
    when (role) {
        DeviceRole.ADULT -> stringResource(R.string.family_role_adult)
        DeviceRole.GRANDPARENT ->
            stringResource(R.string.family_role_grandparent) + " · " + stringResource(R.string.family_role_grandparent_hindi)
    }

@Composable
private fun PhoneInitial(name: String) {
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PendingRow(
    entry: RosterEntry,
    now: Long,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val name = entry.deviceName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.family_unnamed_phone)
    val request = entry.pendingRequest(now)
    val ageText =
        when (val age = request.age) {
            null -> null
            RequestAge.JustNow -> stringResource(R.string.family_request_age_now)
            is RequestAge.Minutes -> stringResource(R.string.family_request_age_minutes, age.count)
            is RequestAge.Hours -> stringResource(R.string.family_request_age_hours, age.count)
        }
    val codeAndAge = listOfNotNull(stringResource(R.string.family_request_code, request.code), ageText).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhoneInitial(name)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    codeAndAge,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    listOfNotNull(if (request.expired) stringResource(R.string.family_request_expired) else null, roleSummary(entry.role))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (request.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onApprove, enabled = request.canApprove, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.family_approve))
            }
            OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.family_deny)) }
        }
    }
}

@Composable
private fun PhoneRow(
    entry: RosterEntry,
    report: DeviceHealth?,
    now: Long,
    onSwitchRole: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val name = entry.deviceName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.family_unnamed_phone)
    val line =
        if (report == null) {
            stringResource(R.string.family_phone_no_report)
        } else {
            val time = DeviceLocalTime.format(report.timezone, now, DateFormat.is24HourFormat(context), locale)
            if (time == null) {
                stringResource(R.string.family_phone_line_no_time, name, report.versionCode)
            } else {
                stringResource(R.string.family_phone_line, name, report.versionCode, time)
            }
        }
    val seen =
        report?.let {
            stringResource(
                R.string.family_phone_seen,
                DateUtils.getRelativeTimeSpanString(it.lastSeenAt, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE),
            )
        }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhoneInitial(name)
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (report == null) name else line,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(if (report == null) line else null, seen, roleSummary(entry.role)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSwitchRole) {
                Text(stringResource(entry.role.other().switchLabelRes()))
            }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.family_revoke), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

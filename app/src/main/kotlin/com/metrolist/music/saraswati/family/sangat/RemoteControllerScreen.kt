/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 Task 19 — the car passenger's remote-controller screen (route sangat/remote), laid out as the
// approved Remote mockup: the host's room, the car's song with progress and who added it, the up-next list, and an
// in-screen "Add a song" sheet. No transport controls and no host actions: nothing plays on this phone. Progress is
// the room's reported position advanced by this phone's clock while the song plays (display only).
package com.metrolist.music.saraswati.family.sangat

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.listentogether.TrackInfo
import kotlinx.coroutines.delay

private const val UP_NEXT_LIMIT = 30

@Composable
fun RemoteControllerScreen(
    navController: NavController,
    viewModel: SangatViewModel = hiltViewModel(),
) {
    val remote by viewModel.remoteController.collectAsStateWithLifecycle()
    val now by viewModel.remoteNowPlaying.collectAsStateWithLifecycle()
    val info by viewModel.roomInfo.collectAsStateWithLifecycle()
    var myName by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { myName = viewModel.savedName() }

    if (!remote) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(LocalPlayerAwareWindowInsets.current).padding(16.dp),
        ) {
            Text(stringResource(R.string.sangat_remote_not_in_car), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { navController.popBackStack() }) { Text(stringResource(R.string.sangat_remote_back)) }
        }
        return
    }

    val hostName = info?.hostName.orEmpty()
    val track = now?.track
    val upNext = now?.queue.orEmpty().take(UP_NEXT_LIMIT)

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(LocalPlayerAwareWindowInsets.current)) {
        RemoteHeader(info = info, onBack = { navController.popBackStack() })

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NowPlayingCard(info = info, track = track, hostName = hostName, myName = myName)
            Text(
                text = stringResource(R.string.sangat_remote_nothing_plays),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = stringResource(R.string.sangat_remote_up_next),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.sangat_remote_up_next_count, upNext.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (upNext.isEmpty()) {
                Text(
                    text = stringResource(R.string.sangat_remote_nothing_queued),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            upNext.forEach { queued ->
                UpNextRow(track = queued, hostName = hostName, myName = myName)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { showAdd = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            ) {
                Icon(painterResource(R.drawable.add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sangat_remote_add), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Text(
                text = stringResource(R.string.sangat_remote_add_caption, hostName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    viewModel.leave()
                    navController.popBackStack()
                },
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(stringResource(R.string.sangat_remote_leave), fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showAdd) AddSongSheet(onDismiss = { showAdd = false })
}

@Composable
private fun RemoteHeader(
    info: SangatRoomInfo?,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.sangat_remote_back))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text =
                    if (info != null) {
                        stringResource(R.string.sangat_remote_title_host, info.hostName)
                    } else {
                        stringResource(R.string.sangat_remote_title)
                    },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (info != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SangatLiveDot()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.sangat_remote_subtitle, info.listening),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (info != null) SangatAvatarStack(names = info.members.map { it.name })
    }
}

@Composable
private fun NowPlayingCard(
    info: SangatRoomInfo?,
    track: TrackInfo?,
    hostName: String,
    myName: String,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.sangat_remote_now).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (track == null) {
                Text(stringResource(R.string.sangat_remote_nothing_yet), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Artwork(url = track.thumbnail, size = 64)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val adder = SangatDisplay.addedBy(track.suggestedBy, hostName, myName)
                    Text(
                        text =
                            stringResource(
                                R.string.sangat_remote_artist_added_by,
                                track.artist,
                                if (adder.isMe) stringResource(R.string.sangat_remote_you) else adder.name,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val position = rememberLivePosition(info, track.id)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(SangatDisplay.clock(position), style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(
                    progress = { SangatDisplay.progress(position, track.duration) },
                    modifier = Modifier.weight(1f),
                )
                Text(SangatDisplay.clock(track.duration), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** The room's reported position, advanced by this phone's clock while the song plays. */
@Composable
private fun rememberLivePosition(
    info: SangatRoomInfo?,
    trackId: String,
): Long {
    val reported = info?.positionMs ?: 0L
    val playing = info?.isPlaying == true
    var elapsed by remember(trackId, reported, playing) { mutableLongStateOf(0L) }
    LaunchedEffect(trackId, reported, playing) {
        if (!playing) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime()
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - start
            delay(1_000L)
        }
    }
    return reported + elapsed
}

@Composable
private fun UpNextRow(
    track: TrackInfo,
    hostName: String,
    myName: String,
) {
    val adder = SangatDisplay.addedBy(track.suggestedBy, hostName, myName)
    val adderLabel = if (adder.isMe) stringResource(R.string.sangat_remote_you) else adder.name
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(url = track.thumbnail, size = 40)
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.sangat_remote_by, adderLabel),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SangatAvatar(name = adder.name, size = 24.dp)
    }
}

@Composable
private fun Artwork(
    url: String,
    size: Int,
) {
    Box(
        modifier =
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(if (size >= 60) 12.dp else 8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(painterResource(R.drawable.music_note), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSongSheet(
    onDismiss: () -> Unit,
    viewModel: SangatAddSongViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val added by viewModel.added.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 16.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sangat_remote_add),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.close), contentDescription = stringResource(R.string.sangat_add_close))
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.sangat_add_search_hint)) },
                leadingIcon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.weight(1f, fill = false)) {
                when (val current = state) {
                    SangatSearchState.Idle -> Unit
                    SangatSearchState.Searching ->
                        Text(stringResource(R.string.sangat_add_searching), style = MaterialTheme.typography.bodyMedium)
                    SangatSearchState.Failed ->
                        Text(
                            stringResource(R.string.sangat_add_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    is SangatSearchState.Results ->
                        if (current.items.isEmpty()) {
                            Text(stringResource(R.string.sangat_add_none), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LazyColumn {
                                items(current.items, key = { it.id }) { song ->
                                    val done = song.id in added
                                    Row(
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Artwork(url = song.thumbnail.orEmpty(), size = 44)
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                song.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                song.artist,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (done) {
                                            FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 44.dp)) {
                                                Text(stringResource(R.string.sangat_add_added))
                                            }
                                        } else {
                                            Button(onClick = { viewModel.add(song) }, modifier = Modifier.heightIn(min = 44.dp)) {
                                                Text(stringResource(R.string.sangat_add_add), fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                }
            }
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sangat_add_done))
            }
        }
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 9 — Saral, the grandparents' easy mode, drawn to the approved
// "Saral — station tiles" mockup: big station tiles, a giant play/pause with
// previous and next, a microphone for voice search, and nothing else. All its
// words are Hindi, set in Noto Sans Devanagari.
//
// It is the whole app while the phone's cached role is grandparent
// (MainActivity), so it deliberately knows nothing about navigation: no
// settings, no long-press anywhere, no link out, and the back gesture is
// swallowed so it cannot finish the screen or reveal anything behind it.
// SaralIsolationTest fails the build if any of that changes.
//
// The only other screen it ever starts is the phone's own speech recogniser
// (RecognizerIntent), for "बोलकर खोजें".
package com.metrolist.music.saraswati.saral

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.saraswati.family.StationIcons

// The approved palette (tokens.css of the mockup). Fixed, not taken from the
// app theme: Saral must look the same on every phone whatever the wallpaper.
private val Background = Color(0xFF141210)
private val Surface2 = Color(0xFF262019)
private val TextPrimary = Color(0xFFF3ECDF)
private val TextDim = Color(0xFFB8AFA0)
private val Accent = Color(0xFFF5B301)
private val AccentInk = Color(0xFF1A1200)
private val TileTints = listOf(Color(0xFF3A2410), Color(0xFF3A2F10), Color(0xFF2F2A10), Color(0xFF1E2A20))

private val NotoSansDevanagari =
    FontFamily(
        Font(R.font.noto_sans_devanagari, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.noto_sans_devanagari, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.noto_sans_devanagari, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )

private val TileLabelStyle = TextStyle(fontFamily = NotoSansDevanagari, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp)
private val StatusStyle = TextStyle(fontFamily = NotoSansDevanagari, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp)
private val IdentityStyle = TextStyle(fontFamily = NotoSansDevanagari, fontSize = 13.sp, letterSpacing = 0.04.em)
private val ListeningStyle = TextStyle(fontFamily = NotoSansDevanagari, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp)
private val ListeningHintStyle = TextStyle(fontFamily = NotoSansDevanagari, fontSize = 16.sp, lineHeight = 22.sp)

@Composable
fun SaralApp(playerConnection: PlayerConnection?) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, onPrimary = AccentInk, background = Background, surface = Background),
    ) {
        SaralScreen(playerConnection)
    }
}

@Composable
fun SaralScreen(
    playerConnection: PlayerConnection?,
    viewModel: SaralViewModel = hiltViewModel(),
) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val voiceActive by viewModel.voiceActive.collectAsStateWithLifecycle()

    // Back does nothing here. It must not finish the screen or surface anything behind it.
    BackHandler {}

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val playback = remember(playerConnection) { playerConnection?.let(::PlayerConnectionPlayback) }
    DisposableEffect(playback) {
        viewModel.bindPlayback(playback)
        onDispose { viewModel.bindPlayback(null) }
    }

    val speech =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.onVoiceResult(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))
            } else {
                viewModel.onVoiceCancelled()
            }
        }
    val songPrompt = stringResource(R.string.saral_say_song)
    val onMic = {
        viewModel.onMicTapped()
        try {
            speech.launch(voiceIntent(songPrompt))
        } catch (e: ActivityNotFoundException) {
            viewModel.onVoiceUnavailable()
        }
    }

    val playingId = (status as? SaralStatus.StationPlaying)?.tile?.station?.id
    val statusText =
        when (val current = status) {
            SaralStatus.Idle -> stringResource(R.string.saral_idle)
            is SaralStatus.StationPlaying -> stringResource(R.string.saral_now_playing, tileLabel(current.tile))
            is SaralStatus.SongPlaying -> stringResource(R.string.saral_now_playing, current.title)
            SaralStatus.VoiceNotFound -> stringResource(R.string.saral_voice_not_found)
            SaralStatus.VoiceUnavailable -> stringResource(R.string.saral_voice_unavailable)
        }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    Tiles(tiles, playingId, viewModel::onTile, Modifier.weight(1f).fillMaxHeight().padding(16.dp))
                    Column(
                        Modifier.width(300.dp).fillMaxHeight().padding(end = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TopRow(onMic)
                        StatusLine(statusText)
                        Transport(isPlaying, viewModel::onPrevious, viewModel::onPlayPause, viewModel::onNext)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopRow(onMic, Modifier.padding(top = 8.dp))
                    Tiles(tiles, playingId, viewModel::onTile, Modifier.weight(1f).fillMaxWidth().padding(16.dp))
                    StatusLine(statusText, Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp))
                    Transport(isPlaying, viewModel::onPrevious, viewModel::onPlayPause, viewModel::onNext, Modifier.padding(bottom = 30.dp))
                }
            }
        }

        if (voiceActive) ListeningOverlay(onClose = viewModel::onVoiceCancelled)
    }
}

private fun voiceIntent(prompt: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

@Composable
private fun tileLabel(tile: SaralTile): String = tile.hindiLabel?.let { stringResource(it) } ?: tile.station.title

@Composable
private fun TopRow(
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.saral_identity), style = IdentityStyle, color = TextDim, modifier = Modifier.weight(1f))
        RoundButton(
            size = 52.dp,
            container = Surface2,
            icon = R.drawable.mic,
            iconSize = 22.dp,
            iconTint = Accent,
            label = stringResource(R.string.saral_voice_search),
            onClick = onMic,
            ring = Accent,
        )
    }
}

@Composable
private fun Tiles(
    tiles: List<SaralTile>,
    playingId: String?,
    onTile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tiles.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.saral_empty), style = StatusStyle, color = TextDim, textAlign = TextAlign.Center)
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEachIndexed { column, tile ->
                    StationTile(
                        tile = tile,
                        tint = TileTints[(row * 2 + column) % TileTints.size],
                        playing = tile.station.id == playingId,
                        onClick = { onTile(tile.station.id) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StationTile(
    tile: SaralTile,
    tint: Color,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = tileLabel(tile)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(80), label = "tilePress")
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.clip(shape)
            .background(tint)
            .border(3.dp, if (playing) Accent else Color.Transparent, shape)
            .semantics(mergeDescendants = true) { selected = playing }
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(StationIcons.drawableFor(tile.station.iconIndex)),
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(40.dp),
        )
        Text(label, style = TileLabelStyle, color = TextPrimary, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = StatusStyle,
        color = TextDim,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Transport(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(
            size = 64.dp,
            container = Surface2,
            icon = R.drawable.skip_previous,
            iconSize = 28.dp,
            iconTint = TextPrimary,
            label = stringResource(R.string.saral_previous),
            onClick = onPrevious,
        )
        RoundButton(
            size = 96.dp,
            container = Accent,
            icon = if (isPlaying) R.drawable.pause else R.drawable.play,
            iconSize = 40.dp,
            iconTint = AccentInk,
            label = stringResource(if (isPlaying) R.string.saral_pause else R.string.saral_play),
            onClick = onPlayPause,
            glow = true,
        )
        RoundButton(
            size = 64.dp,
            container = Surface2,
            icon = R.drawable.skip_next,
            iconSize = 28.dp,
            iconTint = TextPrimary,
            label = stringResource(R.string.saral_next),
            onClick = onNext,
        )
    }
}

@Composable
private fun RoundButton(
    size: Dp,
    container: Color,
    icon: Int,
    iconSize: Dp,
    iconTint: Color,
    label: String,
    onClick: () -> Unit,
    ring: Color? = null,
    glow: Boolean = false,
) {
    Box(
        Modifier
            .size(size)
            .then(if (glow) Modifier.shadow(12.dp, CircleShape, ambientColor = Accent, spotColor = Accent) else Modifier)
            .clip(CircleShape)
            .background(container)
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun ListeningOverlay(onClose: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "listening").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xEB0A0806))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.saral_close),
                role = Role.Button,
                onClick = onClose,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(R.drawable.mic),
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(64.dp).graphicsLayer { alpha = pulse },
        )
        Text(stringResource(R.string.saral_listening), style = ListeningStyle, color = TextPrimary, textAlign = TextAlign.Center)
        Text(stringResource(R.string.saral_say_song), style = ListeningHintStyle, color = TextDim, textAlign = TextAlign.Center)
    }
}

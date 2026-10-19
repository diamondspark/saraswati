/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Task 10 — the festival banner on Home, drawn to the approved
// "Home — festival banner in context" mockup: a warm gradient card (an accent
// on the dark/marigold theme, not a theme change), a diya, the English and
// Hindi greeting, a line saying the radio is one tap away, and a Play button.
// The mockup's photo of a diya is a reference image, not a shippable asset, so
// the diya is the app's own vector. Its colours are the mockup's tokens, fixed,
// so the banner looks the same in every theme.
//
// The banner has no state of its own: the festival comes from the clock
// (rememberCurrentFestival) and the radio is started by the caller.
package com.metrolist.music.saraswati.culture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R

private val BannerFrom = Color(0xFF3A2410)
private val BannerTo = Color(0xFF262019)
private val BannerBorder = Color(0xFF3A2F20)
private val BannerText = Color(0xFFF3ECDF)
private val BannerTextDim = Color(0xFFB8AFA0)
private val BannerAccent = Color(0xFFF5B301)
private val BannerAccentInk = Color(0xFF1A1200)

/** Today's festival, or null; re-read each time the screen comes back and at every midnight. */
@Composable
fun rememberCurrentFestival(): State<Festival?> {
    val clock = remember { FestivalClock() }
    return remember { clock.changes() }.collectAsStateWithLifecycle(initialValue = clock.current())
}

/** [enabled] is false while the radio is being found, so a second tap cannot start a second one. */
@Composable
fun FestivalBanner(
    festival: Festival,
    enabled: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(BannerFrom, BannerTo)))
            .border(1.dp, BannerBorder, shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(BannerTo),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(R.drawable.festival_diya), contentDescription = null, tint = BannerAccent, modifier = Modifier.size(34.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(festival.bannerTitle(), color = BannerText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.festival_banner_subtitle, festival.name),
                color = BannerTextDim,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .alpha(if (enabled) 1f else 0.6f)
                .minimumInteractiveComponentSize()
                .clickable(enabled = enabled, role = Role.Button, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.play),
                color = BannerAccentInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(BannerAccent).padding(horizontal = 13.dp, vertical = 9.dp),
            )
        }
    }
}

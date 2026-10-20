/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: Phase 4 fix wave (Task 19 mockup conformance) — the small pieces the Sangat screens share, taken from
// the approved mockups: the round initial avatars in four warm colours, the overlapping avatar stack, the live dot
// and the "In the car" tag.
package com.metrolist.music.saraswati.family.sangat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R

private val AvatarColors = listOf(Color(0xFFF5B301), Color(0xFFE8925A), Color(0xFF8FC7B4), Color(0xFFD9C27A))
private val AvatarInk = Color(0xFF1A1200)

@Composable
fun SangatAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(AvatarColors[SangatDisplay.colorIndex(name)]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = SangatDisplay.initial(name),
            color = AvatarInk,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

/** Overlapping avatars, as the remote screen's header shows the people in the room. */
@Composable
fun SangatAvatarStack(
    names: List<String>,
    modifier: Modifier = Modifier,
    max: Int = 4,
) {
    Row(modifier = modifier) {
        names.take(max).forEachIndexed { index, name ->
            SangatAvatar(
                name = name,
                size = 28.dp,
                modifier =
                    Modifier
                        .offset(x = (-8 * index).dp)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
            )
        }
    }
}

@Composable
fun SangatLiveDot(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
}

@Composable
fun InCarTag(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Text(
            text = stringResource(R.string.family_sangat_in_car_tag),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

